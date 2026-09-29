# 08 · 线程模型、synchronized 锁升级、Safepoint 与 Handshake

## 学习目标

- 理清 JavaThread ↔ OSThread ↔ pthread 的映射与线程状态机。
- 完整复述 synchronized 的三级锁升级（偏向 → 轻量 → 重量）在源码中的判定链。
- 说清全局 safepoint 的成本来源，以及 JDK 12 handshake 如何拆分部分场景。

---

## 1. 线程体系（`runtime/thread.cpp / thread.hpp`）

```
new Thread().start()
  └─ Thread.start0() (java.base, native) → JVM_StartThread (prims/jvm.cpp)
       └─ JavaThread::run → os::create_thread → pthread_create
            └─ java_entry (os_cpu/linux_x86 等) → thread_entry (jvm.cpp)
                 └─ JavaCalls::call_virtual(run)
```

| 概念 | 源码 | 说明 |
|---|---|---|
| JavaThread | `runtime/thread.hpp` | JVM 视角的 Java 线程：栈、TLAB、safepoint 状态、_handles |
| OSThread | 同上 + `os/posix/*` | OS id、信号掩码、状态 |
| 状态机 | `javaThreadStatus` / `thread.hpp` 中 `_thread_new...` | new→runnable→blocked(on monitor)→wait(sleep)→…；jstack 显示的就是它 |
| 主线程 attach | `Threads::create_vm` → `attach_current_thread` | launcher 线程就是 main 线程 |
| 守护线程 | `VMThread`、GC worker、CompilerThread… | VM 内部线程清单在 `Threads::create_vm` 5 步 |

线程安全点状态存于 JavaThread：`_thread_in_native / _thread_in_vm / _thread_in_Java`——**这就是 safepoint 判定"线程在哪"的依据**（见 §3）。

---

## 2. synchronized：从字节码到 ObjectMonitor

### 2.1 两条路径

```
同步方法：方法的 AccessFlags 带 SYNCHRONIZED → 解释器进 method_entry(zerolocals_synchronized)
同步块：  monitorenter / monitorexit 字节码
          → 模板表（汇编）先走偏向/轻量快路径
          → 失败进 InterpreterRuntime::monitorenter (interpreter/interpreterRuntime.cpp)
```

### 2.2 锁升级判定链（`runtime/biasedLocking.cpp` + `runtime/objectMonitor.cpp` + `runtime/synchronizer.cpp`）

```
进入：读对象 markOop
 ① 低 2 位 =01 且偏向位=1（无锁可偏向）
    → CAS 对象头：线程 id + epoch（biasedLocking.cpp）
    → 成功 = 偏向锁（几乎零成本；之后同线程重入只比较 id）
 ② 偏向撤销（另一线程竞争 / safepoint 批量撤销 bulk rebias-revoke）
    → 撤到无锁，转轻量锁：栈上建 BasicLock（displaced mark word），
      CAS 对象头指向栈上锁记录（runtime/basicLock.cpp）
    → CAS 失败 = 竞争 → inflate()
 ③ ObjectMonitor::inflate (objectMonitor.cpp)：对象头改 00，指向堆外 C++ ObjectMonitor
    → _owner/_cxq/_EntryList/_WaitSet 队列模型（cxq 是 lock-free 链表竞速入队）
    → 阻塞靠 park/unpark（os::PlatformEvent，os/posix/os_posix.cpp）
wait/notify → ObjectMonitor::wait（进 _WaitSet，notify 移回 _cxq/_EntryList）
```

- **重量级监视器在堆外**（malloc，见 `objectMonitor.hpp` 注释），所以只有膨胀才有额外内存。
- 退出与释放的顺序、`cxq → EntryList` 的迁移规则在 `ObjectMonitor::exit`，是读锁源码最烧脑的一段。
- 偏向锁批量重偏向/撤销：`biasedLocking.cpp`，触发条件与 20/40 阈值（`BiasedLockingBulkRebiasThreshold` 等）。

### 2.3 volatile 与内存序

- 字节码层面无差别（ACC_VOLATILE 标志），语义在**访问器生成**处插入内存屏障：`cpu/x86/orderAccess_x86.hpp`（x86 上主要 fence 在 store-load，acquire/release 多数退化）。
- JIT：C1/C2 对 volatile 访问生成 membar 节点；`unsafe.cpp` 的 get/setVolatile 同理。
- 实验：`-XX:+PrintAssembly` 对比 volatile/普通字段赋值的汇编差异。

---

## 3. Safepoint：全局停顿的实现（`runtime/safepoint.cpp`）

### 3.1 机制

```
VM 线程执行 VM_Operation（如 VM_G1CollectForAllocation）
 → SafepointSynchronize::begin()
    遍历所有线程，按状态处理：
      _thread_in_Java   → 等它在"poll 点"自陷（解释器每条字节码边界可检查；
                           编译代码插轮询：读 safepoint page / 递减计数，见 cpu/x86）
      _thread_in_native → 视为已安全，直接记录
      _thread_in_vm     → 有可中断序列点才能停
 → 全部停下后 VM_Operation 才执行
 → SafepointSynchronize::end() 唤醒所有线程
```

- 轮询实现（x86）：编译代码里 `test eax, [polling_page]`——safepoint 时 VM 把页属性改成不可读，访问即 SIGSEGV → 信号处理器接管（`os_posix.cpp` 的 safepoint handler）。**用页保护实现"零成本常态检查"**。
- 成本构成：① 所有线程到齐的等待时间（有长 native 调用会拖）② VM_Operation 本身 ③ 唤醒重启。
- 观察：`-Xlog:safepoint`（每条停顿：何时、为何、耗时）；`jstat -gccause` 也能侧面看。

### 3.2 Handshake（JEP 312，JDK 10 引入，12 已广泛使用：`runtime/handshake.cpp`）

- 动机：很多操作只需要**针对一个线程**（如该线程栈的遍历、偏向锁撤销、`Thread.stop` 状态改变），全局 safepoint 太重。
- 做法：目标线程在下个安全轮询点执行 handshake 回调，**其他线程不感知**；内部复用 safepoint 的轮询点。
- 源码入口：`HandshakeState::process_by_self`、`VM_Handshake*` 操作；调用方举例：`biasedLocking` 的异步撤销、`ThreadServices`。
- 实验对比：制造大量偏向锁竞争（多线程抢同一批对象的锁），分别用 `-Xlog:safepoint` 观察 JDK 12 相对旧版本（概念上）safepoint 次数显著减少（handshake 取代了批量撤销的 STW）。

---

## 4. 动手实验

### 实验 A：jstack 状态机

写两个线程互相 wait/notify 与争抢 synchronized 的程序，`jstack <pid>` 对照 §1 状态表。

### 实验 B：锁升级观测

fastdebug 构建下：`java -XX:+TraceBiasedLocking ...`；或用 JOL 查看对象头随加锁的 bit 变化（偏向→101 线程id / 轻量→00 指向栈 / 膨胀→10 monitor 地址）。

### 实验 C：safepoint 拖延实验

一个线程 `Thread.sleep` 长时间后再进循环 + 触发 GC：用 `-Xlog:safepoint` 观察 `time to safepoint` 被 native/sleep 线程拉长的现象（解释"为什么不要在 run 里写长 native"）。

---

## 5. 自测问题

1. JavaThread 的三种执行状态如何决定 safepoint 等待策略？native 线程为什么"免检"？
2. 偏向锁为什么要在 safepoint（现在多为 handshake）里做批量撤销？
3. ObjectMonitor 的 `_cxq` 和 `_EntryList` 分工？notify 唤醒的线程一定马上拿到锁吗？
4. x86 上 safepoint 轮询为什么选择"读一个受保护的页"而不是内存标志位轮询？
