# 01 · 仓库结构与 HotSpot 总体架构

## 学习目标

- 知道 OpenJDK 仓库里**哪些是 JVM、哪些是类库**，别把 `src/java.*` 当成 JVM。
- 建立 `src/hotspot` 的四象限心智模型：share / cpu / os / os_cpu。
- 记住每个子系统所在目录，后面所有章节都以此为地图。

---

## 1. 仓库顶层结构

```
openjdk12/
├── make/           # 构建系统（autoconf + makefile，gmake 驱动）
├── src/            # ★ 所有源码都在这里
│   ├── hotspot/    # ★ JVM 本体，纯 C++，约 70 万行 —— 本文档的主战场
│   ├── java.base/  # 最核心的 Java 模块（lang/io/nio/invoke + JVM 启动器 native 部分）
│   ├── java.desktop/ java.net.http/ ...   # 其余 60+ 个模块（JEP 200 模块化）
│   ├── jdk.hotspot.agent/  # SA（Serviceability Agent），HSDB 的实现
│   ├── jdk.internal.vm.ci/      # JVMCI：Java 实现的编译器接口（Graal 的基础）
│   ├── jdk.internal.vm.compiler/# Graal 编译器（AOT/JVMCI 使用）
│   ├── demo/ sample/ utils/
├── test/           # jtreg 测试体系（学习源码的"使用手册"）
├── doc/            # 发行说明等文档
├── configure       # 构建配置脚本（autoconf 生成）
└── Makefile
```

**关键认知**：`java.lang.Object` 这些类库代码在 `src/java.base/share/classes/`（Java 语言）；而让这些类能跑起来的虚拟机在 `src/hotspot/`（C++）。两者通过 JNI 和一组内部约定对接（见第 09 章）。

---

## 2. src/hotspot 四象限：share / cpu / os / os_cpu

```
src/hotspot/
├── share/    # 平台无关的 90% 核心逻辑
├── cpu/      # CPU 架构相关：x86/, aarch64/, arm/, ppc/, s390/
├── os/       # 操作系统相关：posix/, linux/, bsd/, windows/, aix/, solaris/
└── os_cpu/   # 两者交集：linux_x86/, bsd_x86/ 等（如线程栈、上下文切换）
```

阅读原则：**先读 share，再按需看 cpu/x86 与 os 的具体实现**。例如锁的内存屏障在 `share/runtime/orderAccess.hpp` 声明，具体指令序列在 `cpu/x86/vm/orderAccess_x86.hpp`。

> 本机是 x86_64 + macOS/Linux，所以配套阅读目录固定为 `cpu/x86/`、`os/posix/`、`os/bsd/`（macOS）或 `os/linux/`、`os_cpu/bsd_x86` 或 `os_cpu/linux_x86`。

---

## 3. share/ 子目录职责地图（背下来）

| 目录 | 职责 | 代表文件 |
|---|---|---|
| `adlc/` | C2 的架构描述文件编译器（把 `.ad` 指令选择描述编译成 C++） | adlc 目录整体 |
| `aot/` | AOT（jaotc）支持 | aotCodeHeap.cpp |
| `asm/` | 汇编器抽象基类 | assembler.cpp, register.cpp |
| `c1/` | **C1 编译器**（client，快速编译） | c1_GraphBuilder.cpp, c1_LIRGenerator.cpp |
| `ci/` | **编译器接口**：JIT 拿到运行时信息的统一门面 | ciEnv.cpp, ciMethod.cpp |
| `classfile/` | **类加载**：解析、校验、SystemDictionary | classFileParser.cpp, systemDictionary.cpp |
| `code/` | Code Cache 与编译产物管理 | codeCache.cpp, codeBlob.cpp, nmethod.cpp |
| `compiler/` | JIT 的 broker/队列/指令选择策略（与具体编译器无关） | compileBroker.cpp, compilerOracle.cpp |
| `gc/` | ★ 全部 GC：shared + 6 种收集器 | 见第 05 章 |
| `interpreter/` | ★ 解释器：模板解释器 + C++ 解释器 | templateInterpreter.cpp, templateTable.cpp |
| `jfr/` | Flight Recorder 实现 | 多个子目录 |
| `jvmci/` | JVMCI 接口实现 | jvmciCompiler.cpp 等 |
| `logging/` | 统一日志框架（`-Xlog`） | log.cpp, logConfiguration.cpp |
| `memory/` | ★ Metaspace、universe、CDS 归档 | metaspace.cpp, universe.cpp, metaspaceShared.cpp |
| `metaprogramming/` | 编译期类型萃取（is_integral 等工具） | 整个目录 |
| `oops/` | ★ 对象模型：oop/klass 体系 | oop.hpp, markOop.hpp, instanceKlass.cpp, method.cpp |
| `opto/` | ★ C2 编译器（server，SSA 图 + 寄存器分配） | compile.cpp, parse1.cpp, chaitin.cpp |
| `prims/` | ★ 对外入口：JNI、JVM_ 函数、Unsafe、MethodHandle | jni.cpp, jvm.cpp, unsafe.cpp |
| `runtime/` | ★ 运行时：线程、锁、safepoint、frame、反射、flag | thread.cpp, objectMonitor.cpp, safepoint.cpp, arguments.cpp |
| `services/` | 可服务性：jcmd 诊断命令、堆转储、内存管理 MBean | diagnosticCommand.cpp, heapDumper.cpp |
| `utilities/` | 基础设施：哈希表、全局缓存、GrowableArray | hashtable.cpp, globalCounter.cpp |

加 `★` 的是本套文档重点覆盖的目录。

---

## 4. HotSpot 总体架构与执行主线

把 JVM 拆成四大块，每一块都有明确的源码锚点：

```
  [java Hello]
       │ ①启动
       ▼
  launcher (java.base/.../launcher/main.c)
       │ JNI_CreateJavaVM
       ▼
  ┌───────────── HotSpot (src/hotspot) ─────────────┐
  │ runtime/threads.cpp : Threads::create_vm        │
  │   ├─ arguments.cpp      解析 -XX/-X 参数          │
  │   ├─ memory/universe.cpp 初始化堆、对象模型        │
  │   └─ systemDictionary   预加载核心类              │
  │                                                 │
  │ ②类加载  classfile/classFileParser.cpp           │
  │           classfile/classLoader.cpp (Java 侧委派) │
  │ ③执行    interpreter/  模板解释器（逐条字节码）     │
  │          compiler/ + c1/ + opto/  热点 → JIT      │
  │ ④内存    gc/  分配 + 回收（对象都从堆上出）         │
  │          memory/metaspace.cpp  类元数据            │
  │ ⑤支撑    runtime/  线程、锁、safepoint、异常        │
  └─────────────────────────────────────────────────┘
       │ ⑥退出：DestroyJavaVM → 退出码
       ▼
  [shell]
```

一条最粗的执行主线（先记住，后面章节逐段展开）：

1. **启动**：`Threads::create_vm()`（`runtime/thread.cpp`）搭建运行时环境。
2. **类加载**：首次用到某类时 `SystemDictionary::resolve_or_null` 触发 Java 层 `ClassLoader.loadClass`，读入 `.class` 字节，`ClassFileParser` 解析成 `instanceKlass`（元数据进 Metaspace）。
3. **执行**：`javaCalls.cpp` 发起 Java 方法调用 → 落到模板解释器逐条执行字节码；调用次数/回边计数达到阈值（`runtime/tieredThresholdPolicy.cpp`）→ 提交给 `compileBroker` → C1/C2 生成机器码 → 后续走编译代码（`code/nmethod.cpp`）。
4. **内存**：`new` 的对象从 TLAB（线程本地缓冲）分配（`gc/shared/`），分代回收由所选收集器（默认 G1，`gc/g1/`）完成。
5. **同步与停顿**：需要全局一致状态的操作（GC、deopt、偏向锁撤销）走 safepoint（`runtime/safepoint.cpp`）或 handshake（`runtime/handshake.cpp`）。

---

## 5. 三个"贯穿性"设计，读源码前必须建立

### 5.1 一切入口皆 C++，Java 侧只是壳

`System.out.println` 最终调到 C++；`new` 是两条字节码（new + invokespecial）→ 解释器里 `TemplateTable::_new` → `InterpreterRuntime::_new` → `CollectedHeap::obj_allocate`。**JVM 的"业务逻辑"几乎都在 hotspot 里**。

### 5.2 双层结构 oop / klass（对象模型的心脏）

- `oop`（ordinary object pointer）：对象在堆上的"实例"部分，只含对象头和字段。
- `Klass`（如 `instanceKlass`）：存"类"的结构信息（方法表、字段布局、vtable），**在 Metaspace 里**，被同类的所有实例共享。
- 每个 oop 的对象头里有个指针指向自己的 Klass，运行时由此知道"我是谁"。

源码：`oops/oop.hpp`、`oops/markOop.hpp`、`oops/klass.hpp`、`oops/instanceKlass.hpp`（第 04 章展开）。

### 5.3 Handle 机制：C++ 层保护 oop 不被 GC 搬走

hotspot C++ 代码持有的 oop 指针是裸指针，GC 移动对象（复制/压缩）时指针会失效。解法是所有跨 GC 点使用的 oop 都包在 `Handle`（`runtime/handles.cpp`）里，Handle 存在线程栈的 HandleMark 区域，GC 扫描时统一更新。**读 hotspot 代码时看到 `Handle h(THREAD, obj)` 都是这个目的。**

---

## 6. 动手实验

1. **数一数**：`find src/hotspot -name '*.cpp' | wc -l`、`find src/hotspot -name '*.hpp' | wc -l`，对 hotspot 规模有体感。
2. **建索引**：编译一次（`bash configure --enable-debug && make images`），然后在 CLion 里打开 `build/.../idea` 工程，把本节地图里的 10 个目录各跳转进去看 1 个文件开头。
3. **第一个断点**：`gdb --args build/*/jdk/bin/java -version`，在 `Threads::create_vm` 和 `ClassLoader::load_class`（`classfile/classLoader.cpp`）下断点，跑一遍观察调用栈。

---

## 7. 自测问题

1. `src/java.base` 和 `src/hotspot` 的分工是什么？`String` 的 `intern()` 逻辑应该在哪个目录找提示？
2. `share/`、`cpu/`、`os/`、`os_cpu/` 各放什么？举一个属于 `os_cpu` 的东西。
3. oop 和 Klass 分别存在内存哪个区域？为什么这样分？
4. Handle 机制解决什么问题？如果没有它会怎样？
