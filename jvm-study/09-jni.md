# 09 · JNI、JVM_ 入口、Unsafe 与 MethodHandle

## 学习目标

- 分清 JVM 暴露给外界的**四类入口**：JNI 表、JVM_ 符号、Unsafe、MethodHandle/invokedynamic。
- 会写一个完整 JNI demo 并在源码中找到每个环节。
- 理解 GC 与 native 引用之间的簿记（handles / JNI local & global refs）。

---

## 1. 四类入口总览

| 入口 | 谁在用 | 实现位置 | 例 |
|---|---|---|---|
| **JNI 函数表** | 所有 native 库（应用/agent） | `prims/jni.cpp` | `NewObject`、`FindClass`、`CallVoidMethod` |
| **JVM_ 符号**（内部 C API） | java.base 类库 native 方法 | `prims/jvm.cpp` | `JVM_StartThread`、`JVM_ArrayCopy`、`JVM_IHashCode` |
| **Unsafe** | JDK 内部 + 库作者 | `prims/unsafe.cpp` | `allocateInstance`、`compareAndSetLong` |
| **MethodHandle / invokedynamic** | lambda、脚本语言 | `prims/methodHandles.cpp` + `java.base/java.lang.invoke` | invokedynamic 适配链 |

另有一条"旁路"：**JVMTI**（agent/profiler 用，`prims/jvmtiEnv.cpp` 一族，事件与能力表）。

---

## 2. `System.arraycopy` 全链路（最值得背的例子）

```
Java: System.arraycopy (java.base)
 → native 方法 JVM_ArrayCopy     ← JVM_ 入口
 → prims/jvm.cpp :: JVM_ArrayCopy
 → oops/objArrayOop.inline.hpp / arrayOop 系列的类型分派
 → GC 屏障感知的批量拷贝（如 G1 的 oop 工具，第 05 章屏障的知识点回串）
```

同理：`Thread.start0 → JVM_StartThread`、`Object.hashCode → JVM_IHashCode`（objectSynchronizer）、`Class.forName0 → JVM_FindClassFromCaller`。**遇到任何 native 方法先去 `jvm.cpp` 找同名 JVM_ 函数**——这是读"类库 ↔ VM"边界的通用钥匙。

---

## 3. JNI 深入（`prims/jni.cpp`）

### 3.1 结构

- `JNIEnv*` 是线程私有函数表二级指针：`JNIEnv_` → `functions`（跳转表，`include/jni.h` 定义）。
- `JavaVM*` 进程唯一：`JNI_GetDefaultJavaVMInitArgs / JNI_CreateJavaVM / JNI_GetCreatedJavaVMs` 三入口（launcher 只用第二个，见第 02 章）。

### 3.2 引用簿记（GC 视角）

| 种类 | 生命周期 | 实现 | 与 GC 的关系 |
|---|---|---|---|
| Local ref | native 方法帧内 | 线程栈的 **HandleArea**（`runtime/handles.cpp`），方法返回整块释放 | Handle 里存 oop 句柄，GC 时统一改写 |
| Global ref | 显式 delete | `runtime/jniHandles.cpp :: _global_handles` 块链 | 是 GC root |
| Weak global ref | 显式 | `jniHandles :: _weak_global_handles` | 可被回收，访问前查活 |

**常见泄漏源**：循环里 `NewGlobalRef` 不 `DeleteGlobalRef`；JNI 返回局部引用被长存（线程块更大，`EnsureLocalCapacity`/PushLocalFrame 控制）。

### 3.3 关键函数的源码锚点

- `FindClass / DefineClass` → `SystemDictionary`（第 03 章）
- `GetObjectClass / GetMethodID` → `instanceKlass` 的方法查找 + `methodHandles` 表填充
- `Call*Method` → `JavaCalls::call_virtual`（`runtime/javaCalls.cpp`）
- `GetStringUTFChars` → 堆内 String → C 字符串拷贝（注意"_pin"与拷贝成本，JDK 12 尚无 compact strings 的 pinned 特性，只做拷贝/UTF8 转换：`oops/symbol.hpp` 与 `javaString` 工具）
- `GetPrimitiveArrayCritical`：短暂拿"不被 GC 搬动"的裸指针（用 GCLocker 实现——`gc/shared/gcLocker.cpp`，期间禁 GC，**必须短**）

---

## 4. Unsafe（`prims/unsafe.cpp`）

为什么它存在：绕过语言层检查做"VM 已经知道安全"的操作，给类库（并发包、nio、堆外内存）用。

| 组 | 代表函数 | 对应 VM 机制 |
|---|---|---|
| 字段寻址 | objectFieldOffset | field layout（第 04 章） |
| CAS | compareAndSetObject/Long | `runtime/atomic.hpp` + cpu/x86 `cmpxchg` |
| 分配 | allocateInstance / allocateMemory | `memAllocator`（不走构造器）/ malloc（堆外） |
| 屏障 | loadFence/storeFence | orderAccess |
| 内存语义 | getObjectAcquire 等 | acquire/release 语义（JDK 9+ VarHandle 的底层） |

JDK 12 里 Unsafe 的 Java 侧在 `jdk.unsupported` 模块——注意它不是 `java.base` 公开 API，`jlink` 时会被警告。

---

## 5. MethodHandle 与 invokedynamic（`prims/methodHandles.cpp`）

- lambda/字符串拼接在 class 文件里都是 `invokedynamic` + BootstrapMethod。
- VM 侧：`JVM_InvokeMethod/...`、MH 适配器**生成代码**（`methodHandles.cpp` 与 `cpu/x86/methodHandles_x86.cpp` 的 adapter 生成）。
- 与反射差异：反射每次走解释路径，MH 可被 JIT 内联（成员 MH 展开成真正的调用序列）——这是 lambda 快于匿名类反射的根因。
- 深入读物：`java.base/java.lang.invoke` 下 `MethodHandle.java`、`LambdaMetafactory` 的 bootstrap 链。

---

## 6. 动手实验

### 实验 A：手写 JNI

```c
// NativeDemo.c
JNIEXPORT jstring JNICALL Java_NativeDemo_hello(JNIEnv *env, jclass c) {
    return (*env)->NewStringUTF(env, "from C");
}
```

javac -h 生成头文件 → 编译 so/dylib → 在 `prims/jni.cpp` 的 `NewStringUTF` 打断点（fastdebug）验证链路。顺带用 `-Xcheck:jni`（fastdebug 自带）观察引用违规报错。

### 实验 B：GCLocker

多线程循环调 `GetPrimitiveArrayCritical` 持有 10ms 再释放，同时跑分配压力：观察 GC 被推迟（`-Xlog:gc` 里 gc locker 相关日志），理解"JNI critical 区拖 GC"。

### 实验 C：Unsafe vs 反射 vs MH 计时

对同一 setter 分别用反射 / Unsafe.putLong / MethodHandle 跑 1 亿次，-Xint 与默认各测一轮——印证第 07 章"MH 可内联、反射不可"。

---

## 7. 自测问题

1. local reference 和 Handle 是什么关系？为什么不能把 oop 裸指针存进全局变量？
2. 一个类库 native 方法（如 `FileInputStream.read0`）如何定位到自己的 JVM_ 函数？（提示：nativeLookup）
3. `GetPrimitiveArrayCritical` 期间 JVM 会发生什么？"必须短"的代价具体是什么？
4. invokedynamic 第一次执行时 VM 做了什么？之后为什么可以和普通 invoke 一样快？
