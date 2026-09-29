# OpenJDK 12 源码系统学习 JVM 指南

> 本套文档基于本仓库（OpenJDK 12 HotSpot 源码）撰写，所有章节都标注了可直接跳转的源码路径。
> 目标：不是背诵八股，而是让你**能顺着一条执行路径把 JVM 的关键机制在源码里走通**。

---

## 0. 这套文档怎么用

三遍学习法：

1. **第一遍（全局）**：按 `01 → 02 → 04 → 05` 的顺序把"架构 → 启动 → 内存 → GC"读一遍，建立地图。不求细节，只求知道"什么东西在哪个目录"。
2. **第二遍（模块）**：挑你最感兴趣的模块（通常先是 GC，然后是 JIT 或锁）按章节精读，每章末尾有"动手实验"，务必做。
3. **第三遍（贯穿）**：用一个真实程序（哪怕只是 `main` 里 `new` 一个对象）从启动、类加载、对象分配、GC 触发、JIT 编译到退出，全程在源码中追踪一遍。

每章结构统一为：**学习目标 → 核心概念 → 关键源码地图 → 深入讲解 → 动手实验 → 自测问题**。

---

## 1. 章节目录

| 章节 | 文件 | 内容 | 预估投入 |
|---|---|---|---|
| 01 | [01-architecture.md](01-architecture.md) | 仓库结构、HotSpot 总体架构、源码地图 | 2h |
| 02 | [02-startup.md](02-startup.md) | `java` 命令到 main 方法的完整启动链路 | 3h |
| 03 | [03-class-loading.md](03-class-loading.md) | 类文件解析、双亲委派、链接、初始化、CDS | 4h |
| 04 | [04-oop-and-memory.md](04-oop-and-memory.md) | oop-klass 对象模型、对象头、运行时内存布局 | 4h |
| 05 | [05-garbage-collection.md](05-garbage-collection.md) | GC 理论、各收集器（G1/Shenandoah/ZGC 等）源码 | 8h+ |
| 06 | [06-interpreter.md](06-interpreter.md) | 模板解释器、字节码执行 | 4h |
| 07 | [07-jit.md](07-jit.md) | 分层编译、C1、C2、Code Cache、去优化 | 6h+ |
| 08 | [08-threads-and-sync.md](08-threads-and-sync.md) | 线程模型、synchronized 锁升级、safepoint/handshake | 5h |
| 09 | [09-jni.md](09-jni.md) | JNI、JVM_ 入口、Unsafe、MethodHandle | 3h |
| 10 | [10-debugging.md](10-debugging.md) | 编译调试 JVM、jcmd、HSDB、日志框架 | 4h |

建议节奏：每天 1~2 小时，6~8 周走完一遍。

---

## 2. 前置知识自查

| 领域 | 最低要求 | 建议补强 |
|---|---|---|
| Java | 熟悉字节码概念、`javap -c` 能看懂 | 《深入理解 Java 虚拟机》第 3 版周志明 |
| C++ | 能读懂类、虚函数、模板、RAII，不用会写 | 读源码时遇到不懂的 C++ 语法随手查 |
| 操作系统 | 虚拟内存、mmap、线程/信号 | 任意 OS 教材对应章节 |
| 汇编 | 能看懂 x86-64 常用指令（mov/lea/cmp/jmp/call） | 解释器和 JIT 章节需要 |

---

## 3. 环境准备

### 3.1 编译（强烈建议，哪怕只是为了生成 IDE 索引）

```bash
# 依赖：macOS 需要 Xcode CommandLineTools；Linux 需要 gcc/g++ 7.4+ 等
bash configure --enable-debug        # 生成 fastdebug 版（带断言，最适合学习）
make images                          # 产出 build/macosx-x86_64-server-fastdebug/jdk/
# 产物验证
build/*/jdk/bin/java -version
```

- `fastdebug`：带 `-Xcheck:jni` 断言与调试符号，调试首选。
- `release`：正式性能版，做性能实验用。

### 3.2 IDE

- **CLion / VSCode**：`make idea-project`（CLion）或 `make vscode-project` 可生成工程索引，跳转源码必备。
- macOS 上 lldb / Linux 上 gdb 直接调试 `bin/java`：

```bash
gdb --args ./java -Xlog:gc* -version
# 常用断点：Threads::create_vm, InstanceKlass::allocate_instance, G1CollectedHeap::do_collection_pause
```

### 3.3 版本要点（JDK 12 相对前代的差异，学习时会反复遇到）

| 变化 | 说明 |
|---|---|
| **Shenandoah GC 正式进入 OpenJDK**（JEP 189） | 本仓库 `src/hotspot/share/gc/shenandoah/` 完整存在；Oracle JDK 构建不含它 |
| G1 可中断的 mixed GC（JEP 344） | `src/hotspot/share/gc/g1/`，mixed collection set 可按剩余时间裁剪 |
| G1 及时归还内存（JEP 346） | 空闲时自动触发 concurrent cycle 并把 committed 内存还给 OS |
| 默认 CDS 归档（JEP 341） | 类数据共享成为默认构建产物，见 `memory/metaspaceShared.cpp`、`memory/filemap.cpp` |
| ZGC 仍为实验性（JEP 333，JDK 11 引入） | `-XX:+UnlockExperimentalVMOptions -XX:+UseZGC` |
| CMS 已弃用（JEP 291，JDK 9 起） | 源码仍在 `gc/cms/`，JDK 14 才删除——是学习"并发标记"历史演进的好样本 |
| 线程 Handshake（JEP 312，JDK 10 引入） | 替代部分全局 safepoint 的逐线程机制，见 `runtime/handshake.cpp` |
| Switch 表达式预览（JEP 325） | Java 语言侧，与 JVM 关系不大，但字节码用了新的 tableswitch 形态 |

---

## 4. 一张图：JVM 与本仓库的对应关系

```
┌────────────────────────────────────────────────────────────┐
│                      JVM 整体架构                            │
│                                                            │
│  ┌──────────────┐   ┌────────────────────────────────────┐ │
│  │  类加载子系统  │   │           运行时数据区              │ │
│  │  加载/链接/初始化│→ │  堆(分代) │ Metaspace │ 线程栈 │ 代码缓存│ │
│  └──────┬───────┘   └──────────────┬─────────────────────┘ │
│         │                          │                        │
│  ┌──────▼──────────────────────────▼─────────────────────┐ │
│  │                执行引擎                                 │ │
│  │   模板解释器   │   C1 (client JIT)  │   C2 (server JIT) │ │
│  └──────┬──────────────────────────┬─────────────────────┘ │
│         │                          │                        │
│  ┌──────▼──────────────────────────▼─────────────────────┐ │
│  │   运行时支撑：线程/锁/safepoint/GC/JNI/JVMTI/JFR        │ │
│  └────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────┘

对应源码（src/hotspot/share/ 下）：
  类加载 → classfile/            数据区 → gc/, memory/, oops/, code/
  执行引擎 → interpreter/, c1/, opto/, compiler/, ci/
  支撑设施 → runtime/, prims/, services/, jfr/
```

---

## 5. 推荐外部资料（配合源码）

- 《深入理解 Java 虚拟机（第 3 版）》— 中文首选入门，先读书建立概念再回源码验证。
- 《Java 虚拟机规范 (Java SE 12 Edition)》— 类文件格式与字节码语义的唯一权威。
- 《HotSpot 实战》/ 《深入拆解 Java 虚拟机》极客时间专栏 — 郑宇迪的 JIT/对象模型系列与源码贴合度高。
- [OpenJDK Group 页面](https://openjdk.org/groups/hotspot/) 与 [HotSpot Internals Wiki](https://wiki.openjdk.org/display/HotSpot/Home) — 概念术语的官方出处。
- 本仓库 `doc/` 目录下的 JDK 12 发行说明与内部文档。

---

## 6. 学习产出检验标准

读完这套文档，你应该能回答：

1. `java Hello` 从 shell 到 `Hello.main` 之间发生了什么（精确到函数名）？
2. `new Object()` 里的对象在内存里长什么样？为什么 64 位机器上一个空对象 16 字节？
3. G1 的 mixed GC 什么时候触发、CSet 怎么选、为什么需要 RSet？和 Shenandoah 的并发整理差在哪？
4. 一段代码从解释执行变成编译执行要经过哪几层？去优化是什么、为什么需要？
5. `synchronized` 锁升级的全过程，偏向锁在哪个条件下撤销？
6. 全局 safepoint 的成本在哪？JDK 12 的 handshake 解决了什么问题？
