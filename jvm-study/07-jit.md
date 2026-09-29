# 07 · 执行引擎（下）：分层编译、C1、C2 与 Code Cache

## 学习目标

- 说清 0~4 五个编译层次的触发条件与分工。
- 走通"热点检测 → 提交编译 → 产出 nmethod → 安装/去优化"的闭环。
- 对 C1 / C2 的流水线差异形成结构认知（不求全懂，先记管线阶段名）。

---

## 1. 分层编译（Tiered Compilation，默认开启）

`runtime/tieredThresholdPolicy.cpp` 是决策中心（`CompileThreshold` 在 tiered 下失效，改由计数器 + 阈值模型）。

| 层 | 编译器 | 触发 | 产物特征 |
|---|---|---|---|
| 0 | 解释器 | — | 计数器随执行累积 |
| 1 | C1（无 profiling） | C2 忙不过来 / trust过的代码 | 快出码，无探针 |
| 2 | C1（带计数 profiling） | C2 队列拥堵的过渡 | 带调用计数 |
| 3 | C1（全 profiling） | **方法/回边计数达标**（默认由 Tier3* 阈值模型控制） | 带分支/类型/参数 profile，喂给 C2 |
| 4 | C2 | level 3 代码再达标，或直接 hot | 最高优化 |

- 计数器存于 `MethodCounters` / `methodData`（`oops/methodCounters.hpp`、`oops/methodData.hpp`）：invocation counter、backedge counter（`-XX:OnStackReplacePercentage`）。
- 典型路径：**0 → 3 → 4**（hot 方法）；**0 → 4**（C2 空闲时跳过）；循环回边达标触发 **OSR（On-Stack Replacement）**，直接替换正在执行的栈帧。

## 2. 编译闭环总览

```
解释执行（method counters 累加）
   │ 达标
   ▼
tieredThresholdPolicy::compile  → CompileBroker::compile_method
   │  compiler/compileBroker.cpp：入 CompileQueue，CompilerThread 消费
   ▼
ciEnv / ciMethod  (ci/*.cpp)      编译器视角的运行时门面（读 klass/method/profile）
   │
   ├─ C1: c1_Compilation.cpp 流水线（见 §3）
   └─ C2: opto/Compile.cpp 流水线（见 §4）
   ▼
生成 nmethod (code/nmethod.cpp) → CodeCache 安装 → 方法入口指针被替换
   │（deopt 或类重定义时）
   ▼
nmethod::make_not_entrant / deoptimization.cpp 回落解释器
```

关键文件：`compiler/compileBroker.cpp`（队列、CompilerThread、C1/C2 选路）、`compiler/compileTask.cpp`、`compiler/compilerOracle.cpp`（`-XX:CompileCommand` 规则引擎）、`runtime/deoptimization.cpp`。

---

## 3. C1 流水线（`c1/`，注重编译速度）

```
字节码
 → GraphBuilder (c1_GraphBuilder.cpp)      生成 HIR（SSA 风格指令）
 → Optimizer/Canonicalizer (c1_Optimizer, c1_Canonicalizer)   常量折叠等局部优化
 → RangeCheckElimination (c1_RangeCheckElimination.cpp)
 → LIRGenerator (c1_LIRGenerator.cpp)      HIR → LIR（低级 IR，接近机器）
 → LinearScan (c1_LinearScan.cpp)          线性扫描寄存器分配
 → LIRAssembler (c1_LIRAssembler.cpp)      逐条 LIR 出汇编
```

特点：单遍、无全局数据流、无内联图爆炸；**profiling 由 GraphBuilder 直接内插**（level 3 的探针来源）。

## 4. C2 流水线（`opto/`，注重峰值性能）

```
Parse (opto/parse1.cpp…)      字节码 → Ideal 图（sea-of-nodes，SSA）
 + 类型传播 (type.cpp)、内联 (bytecodeInfo.cpp 评估, callGenerator 展开)
 + GVN (compile.cpp :: optimize / gvn)      全局值编号去重
 + IfPossible/LoopOpts (loopnode.cpp, ifg…) 循环展开/剥离、范围检查消除、逃逸分析
   （逃逸分析：macro.cpp 的 macro-expand，标量替换/锁消除的前置）
 → PhaseCFG (block.cpp, cfgnode.cpp)        图 → 基本块调度
 → 指令选择：.ad 架构描述 + adlc 生成的 matcher (match.cpp)
 → 寄存器分配：chaitin.cpp（图着色 RA，IFG/coalesce/split）
 → Mach 节点 → 输出汇编 (ad_*.cpp, 平台 cpu/x86/*.ad)
```

配套：`.ad` 描述文件在 `cpu/x86/*.ad`，由 `adlc/` 编译成 C++——这就是 C2 "与平台相关"的全部。

**sea-of-nodes** 是 C2 的心智模型：节点是计算，边是依赖，**控制流本身也是数据**。看懂 `opto/node.hpp` 基类体系后再读优化 pass 会顺很多。

---

## 5. nmethod 与 Code Cache 生命周期

- `nmethod`（`code/nmethod.cpp`）= 编译产物：机器码 + 异常表 + oop map（GC 用）+ 依赖（class loading 时检查能否还成立，`code/dependencies.cpp`）。
- 入口替换：方法被编译后，`Method` 的 `_from_compiled_entry` 指向 nmethod（含一段跳板/内联缓存 IC，`code/compiledIC.cpp`）。
- 失效路径：类卸载（依赖打破）、去优化（乐观假设翻车，如 C2 假设 monomorphic 但来了第二个类型）、CodeCache 满 → sweeper（`runtime/sweeper.cpp`）回收非活跃 nmethod。
- 分堆（04 章提过 3 个 CodeHeap）：C2 放 non-profiled，C1 profiled 放另一堆，隔离 sweeper 扫描成本。

### 去优化（Deoptimization）为什么必然存在

C2 大量优化建立在"预测"上（分支概率、类型单态、逃逸分析）。预测来自 profiling；一旦运行时打脸：编译代码在**安全点埋下的 deopt 点**跳回解释器，并携带 unwind 信息重建解释帧（`deoptimization.cpp :: fetch_unroll_info`）。**没有去优化就没有激进优化**——这是 JIT 设计的核心权衡。

---

## 6. 动手实验

### 实验 A：看层次迁移

```bash
java -XX:+PrintCompilation YourApp
# 输出列：tier(1-4)、方法、是否 OSR(n% 标记)
# 预期：先 3 再 4；trivial 方法可能停在 1
```

### 实验 B：C2 中间产物

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintIdeal -XX:-BackgroundCompilation YourApp
# 看 Ideal 图（sea-of-nodes）；配合 -XX:+PrintCFG 更直观
```

### 实验 C：故意打破单态假设

```java
interface I { int f(); }
static I x = new A();  // 预热 100 万次后切换成 new B() 再跑
// -XX:+PrintCompilation 观察出现 "made not entrant" / deopt
```

用 `-XX:+UnlockDiagnosticVMOptions -XX:+PrintDeoptimizationDetails` 看回落原因（`reason=profile` 的类型预测失效）。

### 实验 D：JVMCI/Graal（选做）

JDK 12 自带 `jdk.internal.vm.ci` 模块：`-XX:+UnlockExperimentalVMOptions -XX:+UseJVMCICompiler` 可用 Graal 替代 C2——体验"用 Java 写的编译器"的入口在 `jvmci/jvmciCompiler.cpp`。

---

## 7. 自测问题

1. 为什么 level 3 要插入 profiling 再给 C2？直接 0→4 有什么损失？
2. OSR 和普通编译的产物在"入口"上有什么本质不同？
3. 内联评估（bytecodeInfo.cpp）考虑哪些因素？为什么热路径小方法内联收益最大？
4. C1 的 LinearScan 和 C2 的图着色 RA 各牺牲了什么换什么？
5. 一个 nmethod 何时被判"not entrant"而非直接删除？
