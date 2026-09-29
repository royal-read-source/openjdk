# 02 · 启动流程：从 `java Hello` 到 `Hello.main`

## 学习目标

- 能在源码里完整复述启动链路的每一站（函数名级）。
- 理解 `JNI_CreateJavaVM` 与 `Threads::create_vm` 各做了什么初始化。
- 会用 `-Xlog` 观察启动阶段。

---

## 1. 完整链路总览

```
shell: java -version Hello
  │
  ├─ ① src/java.base/share/native/launcher/main.c :: main()
  │      解析 java 命令行 → JLI_Launch()
  │
  ├─ ② src/java.base/unix/native/libjli/java.c :: JLI_Launch()
  │      CreateExecutionEnvironment（选 jvm 变体：server/client）
  │      LoadJavaVM()  dlopen("libjvm.so"/libjvm.dylib)，取 JNI_CreateJavaVM 符号
  │      ContinueInNewThread() → 新线程执行 JavaMain()
  │
  ├─ ③ java.c :: JavaMain()
  │      InitializeJVM() → ★ JNI_CreateJavaVM()
  │      LoadMainClass()  找到主类（处理 jar/FX 等包装）
  │      (*env)->CallStaticVoidMethod(env, mainClass, mainID, args)   ← 从这里进入 JVM 内部执行
  │
  ├─ ④ src/hotspot/share/prims/jni.cpp :: JNI_CreateJavaVM()
  │      → Threads::create_vm()
  │
  └─ ⑤ src/hotspot/share/runtime/thread.cpp :: Threads::create_vm()
         （JVM 一切初始化的枢纽，见下节）
         返回后 launcher 侧 CallStaticVoidMethod 通过
         JavaCalls::call_static → 解释器/编译代码 → 你的 main()
```

> 阅读技巧：launcher 侧（①②③）只是"壳"，重点是 ④⑤。Java 侧真正执行 main 前还要先把 `java.base` 模块系统立起来，链路在 `create_vm` 内完成。

---

## 2. `Threads::create_vm` 逐步拆解（runtime/thread.cpp）

按源码顺序（与函数体内的注释块对应），关键步骤：

| 步骤 | 做什么 | 关键函数 / 文件 |
|---|---|---|
| 1 | OS 层初始化：页大小、信号、内存 | `os::init()`（os/ 目录） |
| 2 | 解析 JVM 参数：`-XX/-X`，收集器选择、堆大小推导 | `runtime/arguments.cpp` |
| 3 | `init_globals()`：**JVM 全局数据结构初始化总入口** | `runtime/init.cpp` |
| 3.1 | 对象模型与基本类型就位 | `memory/universe.cpp :: universe_init / initialize_heap` |
| 3.2 | 按参数创建堆与收集器（如 G1） | `gc/g1/g1Arguments.cpp` 等各收集器 `*_Arguments` |
| 3.3 | Metaspace 初始化 | `memory/metaspace.cpp` |
| 3.4 | Code Cache 初始化（3 个堆：non-profiled/profiled/non-nmethod） | `code/codeCache.cpp` |
| 3.5 | 字节码表、解释器 stub 生成 | `interpreter/`，`generate_stubs` |
| 4 | 模块系统初始化：加载 `java.base`，建立模块图 | `classfile/moduleEntry.cpp`、Java 侧 `jdk.internal.module.ModuleBootstrap` |
| 5 | 创建核心系统线程：VMThread、各 GC 工作线程、编译线程、Watchdog | `runtime/vmThread.cpp`、`compiler/compileBroker.cpp` |
| 6 | SystemDictionary 初始化：预解析核心类（Object/String/Thread…） | `classfile/systemDictionary.cpp :: initialize` |
| 7 | 把 launcher 的主线程 attach 成 JavaThread（JNIEnv 诞生） | `Threads::attach_current_thread` |
| 8 | 通知 JVMTI/JFR/JVMCI 等可选设施 | `prims/jvmtiExport.cpp` 等 |

> 注意顺序依赖：**堆 → 类加载设施 → 核心类 → 主线程**。这也是为什么"对象模型"必须最先就位——后面所有初始化都在创建对象。

---

## 3. 主线程如何执行到你的 main

1. launcher 调 `CallStaticVoidMethod`（JNI），进入 `prims/jni.cpp :: jni_CallStaticVoidMethod`。
2. 经 `JavaCalls::call_static`（`runtime/javaCalls.cpp`）构造调用，落到解释器入口（`interpreter/` 生成的 method entry）。
3. 在这之前 JVM 已通过 `load_and_initialize_main_class`（jni.cpp）触发了主类的加载/链接/初始化——这是第 03 章主角。
4. `main` 返回后：`DestroyJavaVM` → 等待非守护线程结束 → 销毁 VM 资源 → 进程退出（`JavaMain` 在 java.c 收尾，处理退出码与异常打印）。

**为什么 `main` 结束了进程不一定退出**：还有非 daemon 线程活着时 `DestroyJavaVM` 会阻塞等待，这就是"JVM 退出条件 = 所有非守护线程结束"的实现位置。

---

## 4. 动手实验

### 实验 A：用日志看启动

```bash
build/*/jdk/bin/java -Xlog:all=info:file=startup.log Hello
grep -E "heap|metaspace|code cache" startup.log
```

单看某一块：

```bash
java -Xlog:gc+init -version          # GC 初始化
java -Xlog:cds -version              # JDK 12 默认 CDS 归档加载（JEP 341）
java -Xlog:class+load -version       # 核心 类预加载（会产生几百行）
```

### 实验 B：GDB 断点启动链

```
b Threads::create_vm
b Arguments::parse
b G1Arguments::initialize          # 换成你选择的收集器
b SystemDictionary::initialize
```

`bt` 观察栈，验证第 2 节的顺序表。

### 实验 C：换个收集器看差异

```bash
java -XX:+UseSerialGC -Xlog:gc+init -version
java -XX:+UseG1GC    -Xlog:gc+init -version
java -XX:+UseShenandoahGC -Xlog:gc+init -version   # OpenJDK 12 独有
```

---

## 5. 自测问题

1. `libjvm.so` 是谁加载的？`JNI_CreateJavaVM` 在哪个文件？
2. `init_globals` 和 `universe_init` 的关系？堆是哪一步被真正提交的？
3. 主线程的 `JNIEnv` 是什么时候创建的？
4. 为什么 `main` 返回后 JVM 可能还在跑？实现位置在哪？
