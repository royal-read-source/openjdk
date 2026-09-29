# 03 · 类加载：解析、双亲委派、链接、初始化与 CDS

## 学习目标

- 掌握 class 文件到 `instanceKlass` 的完整转换过程。
- 在源码层面解释双亲委派、延迟加载、链接三阶段、`<clinit>`。
- 理解 JDK 12 默认 CDS（JEP 341）把启动加快的原理。

---

## 1. 类加载的双侧结构：Java 侧 vs C++ 侧

这是读类加载源码最容易迷路的地方，先建立地图：

```
Java 侧（src/java.base/share/classes/java/lang/ClassLoader.java）
  loadClass() ── 双亲委派、defineClass()
  应用类加载器：jdk.internal.loader.ClassLoaders$AppClassLoader 等

                     ↓ defineClass（native）
C++ 侧（src/hotspot/share/classfile/）
  JVM_DefineClass (prims/jvm.cpp)
    → ClassFileParser::parse_classfile          解析字节流
    → ClassLoader::define_class...              生成 instanceKlass 入 Metaspace
    → SystemDictionary::define_instance_class   登记到 SystemDictionary（全局类表）

触发侧（VM 发现要用一个未加载的类）：
  解释器遇到 new/getstatic 等 → InterpreterRuntime::resolve_*
    → ConstantPool/LinkResolver → SystemDictionary::resolve_or_null
      → 先查 SystemDictionary 缓存 → 没有则回调 Java 层 loadClass
```

| 角色 | 源码位置 | 职责 |
|---|---|---|
| SystemDictionary | `classfile/systemDictionary.cpp` | 全局"类名 → Klass"注册表，加载入口与缓存 |
| ClassLoaderData (CLD) | `classfile/classLoaderData.cpp` | 每个类加载器的元数据域，挂接它加载的所有 Klass |
| ClassFileParser | `classfile/classFileParser.cpp`（**近 1 万行，类加载的心脏**） | class 文件 →内存结构 |
| Verifier | `classfile/verifier.cpp` | 字节码校验（链接的 verify 阶段） |
| LinkResolver | `interpreter/linkResolver.cpp` | 符号引用 → 直接引用（链接的 resolve 阶段） |

---

## 2. 加载、链接、初始化在源码里的落点

### 2.1 加载（Loading）

- 入口：`SystemDictionary::resolve_or_null` → `load_instance_class`。
- 找到字节流后：`ClassFileParser::parse_classfile` 依次解析：

```
magic/version → 常量池(constantPool) → 访问标志 → this/super 类
→ 接口 → 字段(fieldInfo) → 方法(ConstMethod+Method) → 属性
→ BootstrapMethods → (Java 12 class 文件主版本 56)
```

- 解析结果组装成 `instanceKlass`（`oops/instanceKlass.hpp`），元数据从 **Metaspace** 分配（`memory/metaspace.cpp`）。
- `ClassLoaderData` 里还保存该类的常量池、注解、嵌套类关系等。

### 2.2 链接（Linking）三步

| 阶段 | 源码 | 说明 |
|---|---|---|
| Verify | `classfile/verifier.cpp`、`classfile/stackMapTable.cpp` | 字节码流校验；`-Xverify:none` 可关（不推荐） |
| Prepare | `instanceKlass::layout_fields` 相关 | 为静态字段分配空间、设默认值（还不是指定值） |
| Resolve | `interpreter/linkResolver.cpp` | 常量池符号引用 → 直接引用，**惰性**：第一次执行到对应指令才发生 |

方法解析的路径（重点）：`LinkResolver::resolve_invoke` → 按 invokestatic/invokevirtual 等分派 → `lookup_method_in_klasses/...` → 成功后写回常量池缓存（`oops/cpCache.hpp`），第二次直接走缓存。

### 2.3 初始化（Initialization）

- 触发条件即"主动使用"六条（new/getstatic/putstatic/invokestatic/反射/初始化子类…）。
- VM 侧：`instanceKlass::initialize` → 状态机 `fully_initialized`；执行 `<clinit>` 用 `JavaCalls::call_static`（`runtime/javaCalls.cpp`）。
- 保证并发安全：`in_progress` 状态 + 对 class 对象加锁——这就是"静态初始化块天然线程安全"的来源。

### 2.4 双亲委派与打破

- 委派逻辑纯在 Java 侧：`ClassLoader.loadClass`（java.base）。
- C++ 只在 `SystemDictionary::resolve_instance_class_or_null` 里用 `caller loader` 作为起点调用 Java。
- "打破"（SPI/OSGi/热部署）本质是自定义 ClassLoader 里不先 `parent.loadClass`。JDK 12 里平台类加载器与应用类加载器已改为**委托给内置加载器树**（`jdk.internal.loader.BootstrapClassLoader`），不再是简单父子链——读 `jdk/internal/loader/ClassLoaders.java`。

---

## 3. CDS：JDK 12 默认归档（JEP 341）

- 构建时把核心类解析后的元数据**序列化进归档文件**，启动时 `mmap` 直接映射，跳过解析/校验。
- 关键源码：
  - `memory/metaspaceShared.cpp` —— 归档写入与映射（核心）
  - `memory/filemap.cpp` —— 归档文件头/区域布局（mc/rw/ro/md 各 region）
  - `classfile/classListParser.cpp` —— 生成 classlist
- 验证：`java -Xlog:cds -version` 能看到 `Opened archive`；`-Xshare:on` 强制开启失败报错。
- 思考题：为什么 CDS 里**对象**要重新 relocate 而元数据可以直接共享？（元数据只读且地址无关处理，对象有 OOP 需运行时修正——搜 `metaspaceShared.cpp` 里的 relocate/patch 注释。）

---

## 4. 动手实验

### 实验 A：观察类加载顺序

```bash
java -Xlog:class+load=info Hello | head -30
# 第一批一定是 Object、String、Thread 等核心类，且来源标识为 shared objects file（CDS）
```

### 实验 B：自己写类加载器打破委派

写一个 `findClass` 直接读 `.class` 字节再 `defineClass` 的 loader，加载两个同全限定名的类，证明它们的 `instanceKlass` 不同（`Class A != Class B`，且 instanceof 失败）。对应 C++ 侧各有一个 ClassLoaderData。

### 实验 C：链接的惰性

```java
class A { static int x = init(); static int init(){ System.out.println("clinit"); return 1;} }
A.class.getName();          // 只加载+验证，不打印
A.class.getDeclaredField("x").get(null);  // getstatic → 触发初始化，才打印
```

用 `-Xlog:class+init` 确认两步的时间差。

---

## 5. 自测问题

1. `ClassLoader.loadClass` 和 `SystemDictionary::resolve` 各自负责什么？缓存命中发生在哪一层？
2. Prepare 阶段静态字段是什么值？什么时候变成你写的初始值？
3. 常量池解析（resolve）为什么设计成惰性的？结果存在哪里？
4. CDS 为什么能加速启动？它共享的是 .class 文件还是 Klass 元数据？
