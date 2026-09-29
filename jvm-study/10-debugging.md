# 10 · 调试 JVM：编译、GDB/LLDB、jcmd、HSDB 与日志框架

## 学习目标

- 会用 fastdebug 构建 + 调试器断点定位任意 VM 行为。
- 熟练使用统一日志 `-Xlog` 与 jcmd 诊断命令，并知道它们对应的源码。
- 会用 HSDB（SA）离线解剖运行中的 JVM。

---

## 1. 编译与调试器

```bash
bash configure --enable-debug        # fastdebug：断言 + 调试符号，学习首选
make images                          # 或 make exploded-image（更快，直接用 build/*/jdk）
```

- 用 **fastdebug** 学习：断言会立刻暴露你对不变式的误解。
- 断点清单（按学习模块）：

| 模块 | 断点 |
|---|---|
| 启动 | `Threads::create_vm`、`Arguments::parse` |
| 类加载 | `ClassFileParser::parse_classfile`、`SystemDictionary::load_instance_class` |
| 分配 | `MemAllocator::allocate`、`G1CollectedHeap::do_collection_pause` |
| 解释器 | `InterpreterRuntime::resolve_invoke`、`TemplateTable::_new` |
| JIT | `CompileBroker::invoke_compiler_on_method`、`nmethod::post_compiled_method` |
| 锁 | `ObjectMonitor::enter`、`BiasedLocking::revoke` |
| Safepoint | `SafepointSynchronize::begin` |

- macOS 用 lldb：`lldb -- ./java -version`；断点语法 `b Threads::create_vm`。
- 观察 VM 状态的调试器技能：`p markoop` 这类类型可视化靠 `vmStructs`（`runtime/vmStructs.cpp`，SA 与调试器 pretty-printer 的共同地基）。

## 2. 统一日志框架 `-Xlog`（`logging/`）

语法：`-Xlog:<tag1>+<tag2>=<level>:<output>:<decorators>`。

| 主题 | 常用开关 |
|---|---|
| 一切 | `-Xlog:all=info:file=vm.log` |
| GC | `-Xlog:gc*`（等价 gc+gc start/heap/age/ref…） |
| 类加载 | `-Xlog:class+load`、`class+resolve`、`class+init` |
| 启动 | `-Xlog:gc+init`、`start*` |
| safepoint | `-Xlog:safepoint` |
| CDS | `-Xlog:cds` |
| metaspace | `-Xlog:metaspace*` |

实现：tag 表在 `logging/logTag.hpp`（想加自定义日志：定义 tag → `log_info(gc)("...")`）。
注意：JDK 9 后**旧的 `-XX:+PrintGCDetails`、`TraceClassLoading` 等被映射/废弃**，一律优先 `-Xlog`。

## 3. jcmd 与诊断框架（`services/diagnosticCommand.cpp`）

```bash
jcmd <pid> VM.flags            # 当前 -XX 生效值（对应 flag 管理 runtime/globals）
jcmd <pid> VM.metaspace        # Metaspace 明细（memory/metaspace/metaspaceDCmd.cpp）
jcmd <pid> GC.heap_info        # 堆概况（各收集器 CollectedHeap::print_on）
jcmd <pid> GC.class_histogram  # 类直方图（heapInspection）
jcmd <pid> GC.run              # 触发 Full GC
jcmd <pid> Thread.print        # jstack 同款（synchronizer 遍历）
jcmd <pid> Compiler.codecache
jcmd <pid> VM.stringtable / VM.symboltable
jcmd <pid> VM.class_hierarchy
jcmd <pid> Help                # 全量清单
```

注册机制：每条命令是 `DCmd` 子类，在 `diagnosticCommand.cpp` 里注册——想加自己的诊断命令就从这抄一个类。

## 4. 堆转储与 HSDB

- 堆转储：`jcmd <pid> GC.heap_dump /path/dump.hprof`（`services/heapDumper.cpp`）——MAT/JProfiler 分析。
- **HSDB**（Serviceability Agent，`src/jdk.hotspot.agent`）：

```bash
build/*/jdk/bin/jhsdb hsdb        # GUI；或 jhsdb clhsdb --exe java --core core
# File → Attach to pid / open core dump
# 看：对象图、klass、线程栈反汇编、OopMap、监视器
```

- SA 的原理：**不依赖进程存活**，靠 core/attach 读内存 + vmStructs 的偏移表重建对象（所以永远比实时数据"滞后且只读"）。
- 学习用途：把 04 章的对象布局直接"眼见为实"——attach 后 Inspector 看 `new Object()` 的 mark/klass 字。

## 5. 其他可服务性设施

| 设施 | 源码 | 用途 |
|---|---|---|
| JFR | `jfr/` + `src/jdk.jfr` | 低开销事件流（allocation、safepoint、JIT、锁竞争） |
| JVMTI | `prims/jvmti*` | 写 agent 的官方接口（第 09 章） |
| Attach API | `services/attachListener*` | jcmd/jstack 如何"伸手"进运行中的 VM（Unix domain socket） |
| NativeMemoryTracking | `services/memTracker*` | `-XX:NativeMemoryTracking=summary` + `jcmd VM.native_memory` |

JFR 实验：`java -XX:StartFlightRecording=duration=60s,filename=r.jfr YourApp`，用 `jfr print r.jfr | grep -A3 'Allocation outside TLAB'`——把第 05 章的分配路径和真实事件对上。

---

## 6. 综合实战课题（自选 1~2 个）

1. **GC 日志分析报告**：跑一个含内存泄漏的小应用，用 `-Xlog:gc*` + GCViewer 写出：泄漏对象特征、GC 频率/停顿变化、晋升速度估算。
2. **给 VM 加一条日志**：在 `MemAllocator::allocate` 加 `-Xlog:myalloc` 统计每次 OOL 分配大小，重新编译验证——打通"改源码 → 构建 → 观察"全流程。
3. **HSDB 尸检**：故意写内存泄漏 → core dump → HSDB 找到持有链。
4. **解释器 vs C2 微基准**：JMH 跑两版（`-Xint` / 默认），解释差距来源（逐条模板 vs 优化清单：内联/逃逸分析/范围检查消除）。
5. **锁升级时间线**：JFR + `-Xlog:safepoint` 记录一个从偏向到重量级膨胀的全过程，标注每步的源码函数。

---

## 7. 结业自测（全部答出即毕业）

1. `java -Xlog:gc* Hello` 期间，从 `main` 启动到退出，至少发生哪几次 safepoint？分别在哪个 VM_Operation？
2. `new` 一个 100MB 的 `long[]`，走 TLAB 吗？分配路径和 OOM 判定在哪？
3. 为什么改 `.class` 文件做热替换后 C2 代码必须失效？（dependencies 的哪一类？）
4. 把 G1 换成 Shenandoah，写屏障数量变化如何影响你的 C2 编译产物？（用 PrintAssembly 证明）
5. jcmd 是怎么进到 VM 里的？它的每条命令在 VM 侧哪个线程执行？

---

## 8. 常用源码入口速查卡（贴在显示器旁）

```
启动     prims/jni.cpp: JNI_CreateJavaVM   →  runtime/thread.cpp: Threads::create_vm
参数     runtime/arguments.cpp             标志定义  runtime/globals.hpp（+各子系统 *_globals.hpp）
类加载   classfile/classFileParser.cpp · systemDictionary.cpp · classLoader.cpp
对象     oops/oop.hpp · markOop.hpp · instanceKlass.hpp · method.hpp
分配     gc/shared/memAllocator.cpp · collectedHeap.cpp
G1       gc/g1/g1CollectedHeap.cpp · g1ConcurrentMark.cpp · g1DefaultPolicy.cpp
解释器   interpreter/templateInterpreter*.cpp · templateTable_x86.cpp · interpreterRuntime.cpp
JIT      compiler/compileBroker.cpp · tieredThresholdPolicy.cpp · c1/*.cpp · opto/compile.cpp
CodeCache code/codeCache.cpp · nmethod.cpp · runtime/sweeper.cpp
线程锁   runtime/thread.cpp · objectMonitor.cpp · biasedLocking.cpp · synchronizer.cpp
Safepoint runtime/safepoint.cpp · handshake.cpp
JNI      prims/jni.cpp · jvm.cpp · unsafe.cpp · methodHandles.cpp
诊断     services/diagnosticCommand.cpp · heapDumper.cpp · logging/*
```
