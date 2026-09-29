# 05 · 垃圾回收：理论、GC 框架与各收集器源码

## 学习目标

- 建立 GC 的统一框架认知（BarrierSet / CollectedHeap / GC 线程模型），收集器只是插拔件。
- 精读默认收集器 **G1** 的核心数据结构与 mixed GC 流程。
- 理解 JDK 12 新增的 **Shenandoah** 与 ZGC 的并发整理思路差异。

---

## 1. GC 目录总览（JDK 12：6 个收集器 + 1 个共享框架）

```
src/hotspot/share/gc/
├── shared/          ★ 所有收集器共用的基础设施（先读这里）
│   ├── collectedHeap.*        堆抽象基类、TLAB、对象分配入口
│   ├── barriers.*  barrierSet.*  写/读屏障抽象（GC 并发正确性的关键）
│   ├── gctaskManager.*  workgroup.*  GC 工作线程与任务分发（fork-join 式）
│   ├── memAllocator.*    new 的最终落点
│   ├── jvmFlag* / gcTimer / gcTrace ... 日志与追踪
├── serial/          Serial GC（DefNew + Tenured，单线程，教学最佳）
├── parallel/        Parallel Scavenge + Parallel Old（吞吐优先）
├── cms/             CMS（已弃用，JEP 291；学习并发标记的历史实现）
├── g1/              ★ 默认收集器（JDK 9 起），本篇重点
├── shenandoah/      ★ JDK 12 新进（JEP 189，OpenJDK 独有）
├── z/               ZGC（JDK 11 实验，染色指针 + 读屏障）
└── epsilon/         no-op GC（不回收，实验用，面试谈资）
```

收集器选择入口：`runtime/arguments.cpp`（`Arguments::set_gc_topology` 一带），把 `-XX:+Use*GC` 映射到 `CollectedHeap` 与收集策略的实现类。

---

## 2. 必须先懂的四个横切概念

### 2.1 分代与"假说"

弱分代假说（大多对象朝生夕死）驱动 Young/Old 划分。G1 中分代是**逻辑**的：region 属于 eden/survivor/old/humongous 之一，同一对象年龄存在对象头 age 位。

### 2.2 Safepoint：GC 的停顿是"点到点"而非"随时"

所有移动/收缩堆的操作都需要所有线程到达安全点（字节码边界、方法返回处插轮询）。机制见第 08 章，这里只需知道：**任何 GC 的 STW 都从 `SafepointSynchronize::begin` 开始**。

### 2.3 写屏障（Write Barrier）：并发 GC 的地基

- 每次引用字段赋值（`a.f = b`）前后插入的一小段代码。
- G1 用 **SATB**（snapshot-at-the-beginning，标记开始时的对象图快照）：`pre-barrier` 记录旧值；维护 RSet 用 `post-barrier` 记录跨 region 引用（dirty card）。
- 屏障的实现位置：C++ 侧 `gc/shared/barrierSet.*` 与各收集器 `*BarrierSet*`；生成机器码在 `c1/c1_LIRGenerator.cpp`、`opto/`（搜 `store_check`/`g1_write_barrier`）。JIT 编译的字段赋值语句体积变大，根源就在这。

### 2.4 记忆集 / 卡表：跨代（跨 region）引用

- 问题：old → young / region → region 的引用使"只扫描部分区域"失效。
- 实现：G1 的 RememberedSet（`gc/g1/g1RemSet.*`）+ CardTable（`gc/shared/cardTable.*`，每个 region 512B 的 card 粒度）；写屏障把脏卡入 `dirtyCardQueue`（`gc/g1/dirtyCardQueue.*`），GC 时回扫。

---

## 3. G1 精读（默认收集器，`gc/g1/`）

### 3.1 核心数据结构

| 结构 | 源码 | 说明 |
|---|---|---|
| HeapRegion | g1HeapRegion / heapRegion.cpp | 默认 ≈2MB（`-XX:G1HeapRegionSize`，1~32MB 的 2 的幂） |
| G1CollectedHeap | g1CollectedHeap.cpp | 堆本体：region 集合、分配 region 的 `*_alloc_region` |
| CollectionSet (CSet) | g1CollectorState / collectionSetChooser.cpp | 本次 GC 要回收的 region 名单 |
| RSet | g1RemSet.cpp、g1RemSetSummary、heapRegionRemSet | 谁（其他 region）引用了我 → 回收我时不必全堆扫 |
| G1Policy | g1DefaultPolicy.cpp（12 中改名链路：g1Policy） | 何时 GC、CSet 怎么选、期望停顿模型 |
| SATB 队列 | g1SATBCardTable*、satbMarkQueue | 并发标记的正确性保障 |

### 3.2 一次 Young GC 的骨架（`G1CollectedHeap::do_collection_pause` 一带）

```
1. VM_G1CollectForAllocation 提交 VM_Operation → safepoint
2. 选 CSet：全部 eden + survivor；old 候选来自 collectionSetChooser（按 garbage-first 排序）
3. Root 扫描：线程栈、JNI handles、SystemDictionary…（gc/shared/gcRoot*）
4. 复制存活对象：GC 工作线程（WorkGang）并行 evacuat
   - forward 指针写进对象头 mark 字段（markOop 的 marked_for_gc 模式）
   - 引用更新随复制完成（"copying collector + 顺序整理"）
5. 处理 SATB/RSet/dirty card 队列、引用（Reference）、类卸载
6. 释放 CSet region，记录停顿时间 → 反馈给 policy 的停顿预测模型
```

### 3.3 Mixed GC 与 JDK 12 的新东西

- 并发标记：`G1ConcurrentMark`（`g1ConcurrentMark.cpp`，三色标记 + SATB）结束后进入 mixed 阶段：young GC 顺带回收部分 old region。
- **JEP 344（Abortable Mixed）**：mixed 分成两个可能阶段（cleanup remark 后的 normal + abortable），按剩余时间动态丢弃 old 候选——读 `g1Policy.cpp` 里 `abortable` 相关。
- **JEP 346（及时归还内存）**：空闲期由周期任务（`g1PeriodicGCTask`/`G1PeriodicGCInvokesConcurrent`）触发并发 cycle，之后 `shrink` 把 committed 内存还给 OS——云上省钱特性的实现点。

### 3.4 调优参数与源码的对应

| 参数 | 作用 | 源码锚点 |
|---|---|---|
| `-XX:MaxGCPauseMillis`（默认 200ms） | 停顿目标，驱动 CSet 选择 | g1_globals.hpp + g1DefaultPolicy |
| `-XX:InitiatingHeapOccupancyPercent`（默认 45） | 触发并发标记的堆占用 | g1Policy（IHOP 自适应逻辑） |
| `-XX:G1HeapRegionSize` | region 大小 | g1Arguments |
| `-XX:G1MixedGCLiveThresholdPercent` | 存活率超过此值的 old region 不进 CSet | g1DefaultPolicy |

---

## 4. Shenandoah（JEP 189，JDK 12 正式进入 OpenJDK，`gc/shenandoah/`）

与 G1 的根本区别：**回收（evacuation）也是并发的**——工作线程与 Java 线程同时移动对象。

- 关键机制：
  - **Brooks 转发指针**：每个对象头前多一个字，指向"对象本体"（自引用）；对象被搬走后旧位置指向新位置，读写都经过它（`shenandoahForwarding*`，`shenandoahHeap.cpp` 里 `forwarded` 相关）。
  - **读屏障 + 写屏障**（引用字段读写都插桩）保证并发搬迁期间视图一致（`shenandoahBarrierSet*`）。
  - 并发阶段链：init-mark(STW) → concurrent marking → final-mark(STW) → concurrent evacuation → concurrent reference update（`shenandoahConcurrentThread.cpp` 驱动状态机）。
- 学习价值：理解"移动对象而不停世界"需要付出什么（额外一个字的内存、更重的屏障、两轮引用更新）。
- 实验：`java -XX:+UseShenandoahGC -Xlog:gc -jar app.jar`；注意需要 `-XX:+UnlockExperimentalVMOptions` 的场合（低版本 flag 路径）。

## 5. ZGC（`gc/z/`，实验性）与 Epsilon

- ZGC：**染色指针**（64 位地址的高位打 GC 信息标记）+ **读屏障**（load barrier 里"指针自愈"），没有 forwarding pointer，支持 TB 级堆，停顿 <10ms（当年指标）。核心文件：`zAddress.*`（染色布局）、`zHeap.cpp`、`zBarrierSet*`。
- Epsilon：`gc/epsilon/epsilonHeap.cpp`，只分配不回收，用于基准测试对照——读它反而最快搞懂"堆分配器最小面"。

---

## 6. 动手实验

### 实验 A：同一程序跑遍 6 种收集器

```bash
for gc in SerialGC ParallelGC ConcMarkSweepGC G1GC ShenandoahGC ZGC; do
  java -XX:+Use$gc -Xlog:gc* -Xmx1g -version 2>&1 | head -5
done
```

### 实验 B：制造一次 mixed GC 并读日志

写个长期存活的集合 + 大量短命对象，用 `-XX:+UseG1GC -Xms1g -Xmx1g -Xlog:gc,gc+heap=info,gc+marking=info` 观察：初始标记 → 并发标记 → remark → mixed 1..N → cleanup 的完整链，对照 3.2/3.3 的流程。

### 实验 C：JIT 屏障可见性

`java -XX:+UnlockDiagnosticVMOptions -XX:+PrintAssembly -XX:CompileCommand=print,*YourClass.field -version`（需 hsdis），在编译代码里找出 G1 写屏障的 card 标记指令。

---

## 7. 自测问题

1. 为什么"只扫 young 代"需要卡表？没有它最坏情况会发生什么？
2. G1 的 SATB 与 CMS 的增量更新（incremental update）差在哪？各自解决并发标记的哪个丢失问题？
3. JEP 344 让 mixed GC 能"abort"，依据是什么信息决定 abort？
4. Shenandoah 的 Brooks 指针为什么让对象布局多一个字？它和 ZGC 染色指针各自把成本放在了哪里？
5. TLAB 分配失败后到 OOM 之间，JVM 做了哪几步？
