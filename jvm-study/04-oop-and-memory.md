# 04 · 对象模型（oop-klass）与运行时内存布局

## 学习目标

- 画出 `new Object()` 产出的对象在 64 位机器上的逐字节布局。
- 理解 oop / Klass 双层结构及 Metaspace 的内部分配器。
- 掌握五大运行时数据区在源码中的实现位置。

---

## 1. 对象头：markOop 的 64 个 bit

`oops/markOop.hpp` 顶部的位图注释是必背内容（64 位，unlocked 部分为无效位）：

```
|  unused:25 | identity_hash:31 | unused:1 | age:4 | biased_lock:1 | lock:2 |
```

`lock` 两位（无偏向锁时）：

| lock | biased_lock | 状态 | 对应源码 |
|---|---|---|---|
| 01 | 0 | **unlocked**（轻量锁/无锁） | `oops/markOop.inline.hpp` |
| 00 | — | **inflated**（重量级，指向 ObjectMonitor） | `runtime/objectMonitor.cpp` |
| 10 | — | **marked for GC** | GC 标记阶段 |
| 11 | — | **biased locked**（偏向锁，存线程 ID） | `runtime/biasedLocking.cpp` |

- identity_hash：首次 `Object.hashCode()` 生成，从此**存在对象头里不再变**（`synchronizer.cpp::get_next_hash`）。
- age：分代年龄，每次 Young GC 存活 +1，满 15（`MaxTenuringThreshold`）晋升。
- biased_lock：JDK 15 后移除，12 中默认开启（`-XX:-UseBiasedLocking` 可关）。

## 2. 一个对象的完整布局（64 位，未开压缩指针时 +8 字节每引用）

```
┌────────────────────┐ 低地址
│ markOop  (8B)      │ 哈希/GC 年龄/锁
├────────────────────┤
│ klass 指针 (4B/8B) │ → Metaspace 里的 instanceKlass（压缩后 4B）
├────────────────────┤
│ 实例字段...        │ 按 oops/fieldInfo + instanceKlass 的布局
├────────────────────┤
│ padding 到 8B 对齐 │（alignment 相关：-XX:ObjectAlignmentInBytes）
└────────────────────┘ 高地址
```

所以 `new Object()` = mark(8) + klass(4，压缩) + padding(4) = **16 字节**。
压缩指针（默认开，堆 <32GB）：`oops/compressedOops.inline.hpp`，本质是 4 字节偏移量 × 8 加基址；`-Xmx` 超过压缩阈值则退化为 8 字节裸指针。

## 3. oops 体系速查（都在 `oops/` 下）

| 类型 | 文件 | 说明 |
|---|---|---|
| oopDesc | `oop.hpp` | 一切对象的基类，只有 `_mark` 和 `_metadata` |
| instanceOop | `instanceOop.hpp` | Java 普通对象，额外 0 字节（紧跟字段） |
| arrayOop | `arrayOop.hpp` | 数组头：多一个 `_length` |
| objArrayOop / typeArrayOop | 同名文件 | 对象数组 / 基本类型数组 |
| markOop | `markOop.hpp` | 不是 oop！是 64 位字的强类型封装 |
| instanceKlass | `instanceKlass.hpp/cpp` | 类元数据：vtable、字段布局、方法数组、状态机 |
| arrayKlass / objArrayKlass / typeArrayKlass | 同名文件 | 数组类的元数据（运行时合成，无 .class 来源） |
| method / constMethod | `method.hpp` / `constMethod.hpp` | 方法对象：Method 含解释器入口/JIT 状态，ConstMethod 存字节码本体 |
| constantPool / cpCache | `constantPool.hpp` / `cpCache.hpp` | 常量池与"解析后缓存"（linkResolver 的加速器） |
| symbol | `symbol.hpp` | intern 化的类名/方法名（VM 内部全局唯一表，`utilities/hashtable`） |

**Klass 之间的关系**：`klass.hpp` 里 `Klass` → `InstanceKlass` / `ArrayKlass` →（`ObjArrayKlass`、`TypeArrayKlass`）。`InstanceClassLoaderKlass`、`InstanceMirrorKlass`、`InstanceRefKlass` 是三种特殊 InstanceKlass（分别处理加载器对象、Class 镜像、Reference 语义），在 GC 扫描时走特殊路径。

## 4. 运行时数据区 → 源码地图

### 4.1 Java 堆

- 抽象基类：`gc/shared/collectedHeap.cpp`（`allocate_raw`、TLAB 管理）。
- 具体堆随收集器：G1 `gc/g1/g1CollectedHeap.cpp`；Parallel `gc/parallel/parallelScavengeHeap.cpp`；Serial `gc/serial/serialHeap.hpp`。
- 分代结构（G1 时代已不是物理连续两代，而是逻辑代 + region；第 05 章展开）。
- 分配主路径：`new` → TLAB 内 bump-the-pointer（一次 CAS 或纯指针推进）→ TLAB 满则新 TLAB → 堆顶 CAS（`MemAllocator`，`gc/shared/memAllocator.cpp`）→ OOM 判定 + GC 重试。

### 4.2 Metaspace（取代永久代的类元数据区）

- 顶层：`memory/metaspace.cpp`（`Metaspace::allocate` 入口）。
- 内部分配器（`memory/metaspace/`，JDK 12 已做组件化重构）：
  - `virtualSpaceList*` —— 从 OS 要的大块（64K chunk 为粒度起步）
  - `chunkManager.cpp` —— chunk 级分配回收
  - `spaceManager.cpp` —— 每个类加载器一个，管理它自己的小块
  - `metachunk.cpp` / `metablock.hpp` —— chunk 内切 block
- 关键机制：**每个 ClassLoaderData 一个 SpaceManager** → 类加载器可被整体卸载（元数据整块归还）。
- 大小控制：`-XX:MetaspaceSize`（首次 GC 阈值）与 `-XX:MaxMetaspaceSize`；用 `jcmd VM.metaspace` 观察（`metaspace/metaspaceDCmd.cpp`）。

### 4.3 线程栈

- `runtime/thread.cpp` 创建 `OSThread` 后由 `os::create_thread` 分配栈（`os/posix/...`），大小 `-Xss`。
- 栈帧模型：`runtime/frame.cpp`（抽象帧）+ `cpu/x86/...`（物理布局）；解释器帧、JIT 帧布局不同，`frame::sender` 负责穿链。
- 深递归 OOM 的判定点：解释器/编译代码里的栈 banging（写入哨兵页触发 StackOverflow）。

### 4.4 Code Cache

- `code/codeCache.cpp`：JDK 12 分 **3 个 CodeHeap**：non-profiled nmethods（C2，最高频）、profiled nmethods（C1）、non-nmethod（stub/适配器/解释器代码）。
- 编译产物 `nmethod`（`code/nmethod.cpp`）；清理靠 sweeper（`runtime/sweeper.cpp`）。
- `jcmd Compiler.codecache`、`-XX:ReservedCodeCacheSize`。

### 4.5 直接内存与其他

- DirectByteBuffer 的 native 内存不算任何 JVM 区，受 `MaxDirectMemorySize` 约束（Cleaner 释放，JDK 9+）。
- JVM 自身 native 内存：`-XX:NativeMemoryTracking=summary` + `jcmd VM.native_memory`（`services/memTracker.cpp` 一族）。

## 5. 动手实验

### 实验 A：用 JOL 验证布局

```xml
<dependency><groupId>org.openjdk.jol</groupId><artifactId>jol-core</artifactId><version>0.17</version></dependency>
```
```java
System.out.println(ClassLayout.parseInstance(new Object()).toPrintable());
// 验证：12B 对象 + 填充 = 16B；打开 -XX:-UseCompressedOops 再看 klass 变 8B
```

### 实验 B：观察 Metaspace 分配

```bash
java -XX:NativeMemoryTracking=summary -Xlog:metaspace* -version
jcmd <pid> VM.metaspace | head -40
```

### 实验 C：Code Cache 三段

```bash
java -XX:+PrintFlagsFinal -version | grep CodeHeap
jcmd <pid> Compiler.codecache
```

## 6. 自测问题

1. markOop 里哪几位在 GC 后会变？哪几位在对象生命周期内基本不变？
2. 为什么空对象是 16 字节？压缩指针开启与关闭分别怎么算？
3. Metaspace 与堆谁先 OOM？`MetaspaceSize` 超过后发生什么？
4. 一个 `.class` 里的方法字节码存在哪个结构里？invokespecial 的解析结果缓存在哪？
