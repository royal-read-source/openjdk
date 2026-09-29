# 06 · 执行引擎（上）：模板解释器与字节码执行

## 学习目标

- 理解 HotSpot 为什么用"模板解释器"：启动时**用汇编为每条字节码生成机器码**。
- 能追踪一条字节码（如 `new`、`invokevirtual`）从执行到回落运行时的完整路径。
- 分清 C++ 解释器与模板解释器的角色分工。

---

## 1. 两套解释器

| | 模板解释器（默认） | C++ 解释器 |
|---|---|---|
| 实现 | `templateInterpreter.cpp` + `templateTable.cpp` + `cpu/x86/templateTable_x86.cpp` | `cppInterpreter.cpp` + `bytecodeInterpreter.cpp` |
| 原理 | 启动时逐条字节码**生成汇编**（Template::generate），执行零解释开销 | 用 C++ 大 switch 模拟栈式机（`bytecodeInterpreter.cpp` 中心循环） |
| 用途 | 生产执行 | 调试/实验（`-Xint` + 不支持模板的平台）、JVMCI 实验路径 |
| 性能 | 快 | 明显慢（每条指令一次 C++ 分发） |

切换实验：`-Xint` 强制纯解释执行（仍用模板解释器），`-XX:-UseTemplateInterpreter`（部分构建）才能逼出 C++ 解释器——用于对照。

---

## 2. 模板解释器的装配过程（读源码主线）

### 2.1 生成入口点

`templateInterpreterGenerator_x86.cpp :: generate_all()` 为关键情形生成**方法入口 stub**：

```
method_entry(zerolocals)        普通 Java 方法
method_entry(zerolocals_synchronized)   synchronized 方法
native_entry                    native 方法（JNI）
exception_entry                 异常处理分发
```

每个方法的 `Method` 结构里存着自己的入口地址（`oops/method.hpp` 的 `_from_compiled_entry/_i2i_entry/_from_interpreted_entry`），调用方取 `method` 的入口跳转即可——**方法调用统一为"取入口 → jump"**。

### 2.2 字节码模板表

- `templateTable.cpp` 定义每条字节码 → 模板函数的映射：`set_unimplemented / def(Bytecodes::_new, ..., &TemplateTable::_new)`。
- `cpu/x86/templateTable_x86.cpp` 给出每条模板的**手写汇编**。例如 `_new` 的汇编骨架：

```asm
# 伪代码（真实汇编见 templateTable_x86.cpp :: _new）
检查常量池缓存是否已解析 → 未解析: call InterpreterRuntime::resolve_getstatic...
检查 TLAB 剩余空间 → 不足: call InterpreterRuntime::new...（走慢路径+GC）
TLAB bump 分配，写 markOop(原型) + klass 指针
调用 <init>（CommonAsmRoutine / 快速路径 null check）
```

### 2.3 主分发循环

`templateInterpreterGenerator_x86.cpp :: generate_normal_entry` 尾部进入 dispatch：

```asm
movzbw rax, [rbx]        # rbx=字节码指针，取 opcode
movabs rcx, dispatch_table[opcode*8]
jmp rcx                  # 直接表驱动跳转，无 switch
```

"执行一条字节码"= 跳到对应模板汇编；模板结尾再次 dispatch。

---

## 3. 解释器 ↔ 运行时（C++）的桥：InterpreterRuntime

模板是纯汇编，遇到复杂逻辑（分配失败、解析、异常、锁）必须回 C++。落点全部在 `interpreter/interpreterRuntime.cpp`：

| 字节码场景 | 慢路径函数 | 后续去向 |
|---|---|---|
| `new` / `newarray` | `InterpreterRuntime::_new` | `MemAllocator` 分配（见 04 章） |
| `invoke*` 首次 | `InterpreterRuntime::resolve_invoke` | `linkResolver.cpp` 解析 + 写 cpCache |
| `get/putfield` 未解析 | `resolve_get/put` | 同上 |
| `monitorenter/exit` | `_monitorenter/_monitorexit` | `runtime/objectMonitor.cpp`（第 08 章） |
| 异常 throw | `InterpreterRuntime::exception_handler_for_exception` | 栈上找 handler 表 |

**快慢路径二分法**是 HotSpot 的通用套路：汇编里做常见快路径（检查 1 个标志位），失败 `call` 进 C++ 慢路径，回来后重试快路径。

---

## 4. 解释器栈帧布局（x86-64）

```
高地址
┌────────────────────────┐
│ 被调方保存的 caller 参数 │  frame::interpreter_frame_*
├────────────────────────┤
│ 局部变量区 locals       │  ← 寄存器 rlocals 指向第一个 local
├────────────────────────┤
│ 表达式栈（随用随长）    │  ← rsp 即栈顶
├────────────────────────┤
│ frame 元数据：          │
│  bcp(字节码指针) cpcache │
│  method ptr / sender_sp │
└────────────────────────┘
```

配套寄存器约定（`cpu/x86/interp_masm_x86.cpp`）：`rbx=bcp`、`rcx/cx=method`、`rlocals`、`r13/esp`。调试 `PrintAssembly` 输出时靠这些认帧。

方法返回时 `restore_bcp/pop frame/jmp *return_address`——没有"返回值类型 switch"（寄存器约定完成）。

---

## 5. 动手实验

### 实验 A：打印生成的解释器代码

```bash
java -XX:+PrintInterpreter -version 2>&1 | head -60
# 看到 [Dispatch Table]、每条字节码的汇编 —— 对着 templateTable_x86.cpp 读
```

### 实验 B：追一条 `new`

```java
class T { public static void main(String[] a){ Object o = new Object(); } }
java -XX:+PrintInterpreter T   # 找 Bytecodes::_new 段
```

再配合 `-Xlog:bytecode+...` 或在 `InterpreterRuntime::_new` 上打 gdb 断点（fastdebug 构建）确认慢路径触发条件。

### 实验 C：方法调用解析

写一个有接口/虚方法/静态方法的类，`javap -c` 看常量池；在 `LinkResolver::resolve_invoke` 下断点，观察每个方法第一次调用才进来（cpCache 命中后不再触发）。

---

## 6. 自测问题

1. 模板解释器"启动时生成汇编"比 C++ switch 解释器快在哪两个层面？
2. `Method` 里至少有哪几个入口指针？分别给谁用？
3. 为什么快慢路径必须"汇编做检查、C++ 做脏活"，反过来行不行？
4. 解释器帧里 bcp 和 method 为什么要保存在帧里而不是寄存器跨调用保存？
