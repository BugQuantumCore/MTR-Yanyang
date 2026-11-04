# v1.0.7 修复说明：切换轨道路径算法后崩溃且无法重新进入世界（客户端接收器 lambda 签名错误）

## 现象（latest.log 铁证）

用户在单人世界用 Shape tool 把一条轨道切换为 `cubic_parabola` 后：

```
[21:26:34] [Server thread/INFO]: Broadcasting shape cubic_parabola for RailPair[...] to 1 player(s) via 1 key(s)   ← 1.0.6 修复后服务器广播首次真正工作
[21:26:34] [Server thread/INFO]: Shape tool at node -7, -60, -237: 1 of 1 connection(s) -> cubic_parabola
[21:26:34] [Netty Local Client IO #0/ERROR]: Encountered exception while handling in channel with name "yanyang_easement:shape_sync"
java.lang.AbstractMethodError: Receiver class com.bugquantumcore.yanyang.easement.client.YanyangEasementModClient$$Lambda/0x...9e001cd8
  does not define or inherit an implementation of the resolved method
  'abstract void receive(net.minecraft.class_310, net.minecraft.class_634, net.minecraft.class_2540,
   net.fabricmc.fabric.api.networking.v1.PacketSender)'
  of interface net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$PlayChannelHandler.
    at net.fabricmc.fabric.impl.networking.client.ClientPlayNetworkAddon.receive(ClientPlayNetworkAddon.java:98)
[21:26:34] [Server thread/INFO]: BugQuantumCore lost connection: 连接中断        ← 客户端被踢
[21:26:34] [Server thread/INFO]: Stopping singleplayer server as player logged out
```

**"无法正常重启"的死循环**：重新进入世界时服务器在 JOIN 事件里重放全部已保存形状——

```
[21:26:44] [Server thread/INFO]: Join sync for BugQuantumCore: 2 eased shape(s) among 4 rail connection(s)
[21:26:44] [Netty Local Client IO #1/ERROR]: Encountered exception ... AbstractMethodError ...（同上，逐字一致）
[21:26:44] [Server thread/INFO]: BugQuantumCore lost connection: 连接中断
```

即：**只要世界里存在任意 eased shape，客户端一进入世界就会断开连接**，世界因此"打不开"。
（最后还有一条重要旁证：`[client] Rail shape resolution ... byStamp=4, byMirror=0, byAnteConfigs=0, miss=26`
—— 客户端镜像 byMirror 恒为 0，因为 shape_sync 包从未被成功接收过。这也是此前"轨道外观不生效"
问题的最终成因之一。）

## 根因：1.0.4 重编译把 shape_sync 客户端接收 lambda 的 SAM 签名编译错了

### 机制

`YanyangEasementModClient.onInitializeClient()` 注册接收器：

```java
ClientPlayNetworking.registerGlobalReceiver(SHAPE_SYNC_CHANNEL,
    (client, handler, buf, responseSender) -> { ... });
```

Java lambda 经 `invokedynamic + LambdaMetafactory` 生成运行时类，实现**编译时接口的 SAM 方法**。
编译时 stub（workspace-105，1.0.4 起使用）把接口写成了：

```java
public interface PlayChannelHandler {
    void receive(class_310 client, Object handler, class_2540 buf, Object responseSender);  // ← 第 2/4 参数是 Object
}
```

（当时 stub 集里没有 `class_634`（ClientPlayNetworkHandler）和 `PacketSender` 两个类型，图省事用了 Object。）

而运行时真实 Fabric API（fabric-networking-api-v1-1.3.15+0caff8d077）的接口方法是：

```java
void receive(class_310 client, class_634 handler, class_2540 buf, PacketSender responseSender);
```

**方法描述符不一致**（`Ljava/lang/Object;` vs `Lnet/minecraft/class_634;` / `Lnet/fabricmc/.../PacketSender;`）。
Fabric API 的 `ClientPlayNetworkAddon.receive` 以 invokeinterface 按精确签名调用 → JVM 在 lambda
类上找不到该方法 → `AbstractMethodError` → Netty 通道异常 → 断开连接。

### 六版本考古（对 1.0.1~1.0.6 全部 jar 的常量池逐版本反汇编）

| 版本 | `YanyangEasementModClient` 的 receive lambda 签名 | 状态 |
|---|---|---|
| 1.0.1 ~ 1.0.3 | `(class_310, class_634, class_2540, PacketSender)V` | ✅ 正确（原始字节码） |
| 1.0.4 ~ 1.0.6 | `(class_310, Object, class_2540, Object)V` | ❌ 坏签名（stub 重编译引入） |

同时 `YanyangEasementMod`（JOIN 事件的 `onPlayReady(class_3244, PacketSender, MinecraftServer)`
lambda）在全部版本中都是正确签名、从未被重编译 —— 这解释了为什么 Join sync 日志能正常打印、
而 shape_sync 一到客户端就崩。

### 为什么潜伏了两个版本才爆发

1.0.4 引入坏签名的同时，`PacketTrainDataGuiServerMixin` 也因注解保留期回归而**静默死亡** ——
服务器从不广播 shape_sync，坏 lambda 从未被调用，于是"不崩溃、外观也不生效"（byMirror=0）。
1.0.6 修好 mixin、广播链路首次打通，第一次真正发出 shape_sync 包时，这个潜伏的
AbstractMethodError 立即爆发。**这是 1.0.6 修复的"下一环"，也是整条链路的最后一环。**

（前情同类案例：`class_2540.writeLong` 的签名回归曾在 1.0.4 修复过 —— 见 stub 注释与
FIX-1.0.4。本次是它的姊妹问题：一个坏在方法返回类型（NoSuchMethodError），一个坏在
函数式接口 SAM 描述符（AbstractMethodError）。）

## 修复（v1.0.7，最小改动面）

1. **修正 stub**（workspace-107/stubs）：
   - `ClientPlayNetworking.PlayChannelHandler.receive` 第 2/4 参数改为精确类型
     `net.minecraft.class_634` / `net.fabricmc.fabric.api.networking.v1.PacketSender`；
   - 新增 stub：`net/minecraft/class_634.java`（ClientPlayNetworkHandler 空壳，mod 未调用其成员）、
     `net/minecraft/class_2596.java`（Packet）、
     `net/fabricmc/fabric/api/networking/v1/PacketSender.java`（sendPacket 形状保真）；
   - 顺带修正 `ServerPlayConnectionEvents.Join.onPlayReady` 第 2 参数 Object → PacketSender
     （当前 mod 类不受影响，防未来重编译回归）；
   - `registerGlobalReceiver` stub 记录 handler 供冒烟测试调用。
2. **仅重编译受害的 1 个类**（ecj 3.33.0，`-source 17 -target 17`，
   classpath = 修正后 stub 集 + 1.0.6 jar）：`YanyangEasementModClient.class`。
   产物 lambda SAM 描述符恢复为
   `(Lclass_310;Lclass_634;Lclass_2540;Lnet/fabricmc/fabric/api/networking/v1/PacketSender;)V`
   —— 与 1.0.1~1.0.3 原始字节码及运行时 Fabric API 完全一致。
3. **打包为 `yanyang-easement-mtr3x-1.0.7.jar`**：自 1.0.6 jar 复制全部 51 个条目（顺序、
   时间戳、压缩设置保留），仅替换 `YanyangEasementModClient.class` 与 `fabric.mod.json`
   版本号 1.0.6 → 1.0.7。**其余类逐字节不变**（含 1.0.6 修好的两个 mixin）。

## 验证（全部通过）

1. **常量池反汇编**：新类 lambda SAM 描述符 = 运行时 Fabric API 精确签名（True），
   旧坏签名零残留（False）；六版本考古表见上。
2. **决定性冒烟测试（Y107SmokeTest，含阴性对照）**：
   - 在 stub classpath 上执行 `onInitializeClient()` → 取出注册的 handler →
     **反射按精确签名解析 receive（与 JVM 运行时方法解析语义等价）→ 直接以
     invokeinterface 调用 `receive(client, handler, buf, sender)`** —— 与
     `ClientPlayNetworkAddon.receive:98` 完全相同的调用路径：
     - 新 1.0.7 字节码：成功，输出 `[INFO] Received shape sync: cubic_parabola for
       RailPair[...] in ...`，无任何 AbstractMethodError；
     - 阴性对照（旧 1.0.6 字节码）：抛出的 `AbstractMethodError` 消息与游戏内崩溃栈
       **逐字一致**（同异常类、同方法签名、同消息格式、同 `$$Lambda/0x...` 运行时类形态）
       —— 证明根因诊断、测试方法、修复三者全部正确。
3. **全量链接测试**：25/25 类在修正 stub classpath + 1.0.7 jar 上 `Class.forName` +
   构造器/方法/字段反射解析通过。
4. **最终 jar 冒烟**：直接用 1.0.7 jar 内的类重跑上述测试，通过。
5. **字节一致性**：1.0.7 与 1.0.6 逐条目 diff，仅 `YanyangEasementModClient.class` +
   `fabric.mod.json` 两个预期条目差异，51 条目顺序完全一致。
6. **mixin 注解审计（字节级 RuntimeVisibleAnnotations，语义等价于 Mixin 转换器
   "只读 visible 注解"）**：`RailMixin @Inject=4 @Shadow=17 @Unique=11`、
   `PacketTrainDataGuiServerMixin @Inject=1`、`RailDataCarrierMixin`、`RailPersistenceMixin`、
   `RailwayDataAccessor` 全部可见 —— 1.0.6 的注解修复在 1.0.7 中完好无损。

## 安装

1. 关闭游戏；
2. 将 `yanyang-easement-mtr3x-1.0.7.jar` 放入 `mods/` 文件夹，**删除**
   `yanyang-easement-mtr3x-1.0.6.jar`（不要同时保留两个版本）；
3. 启动游戏，进入之前"打不开"的世界 —— Join sync 会重放 2 个 eased shape，
   客户端应正常打出 `Received shape sync: ...` 日志并保持连接；
4. 再次用 Shape tool 切换任意轨道的线型，应看到：
   - 服务器：`Broadcasting shape <算法> for RailPair[...] ...`
   - 客户端：`Received shape sync: <算法> for RailPair[...] ...`
   - 轨道外观立即更新（此前 6 个版本从未真正生效的最后一环）。

## 预期游戏内日志（修复成功的标志）

```
[Server thread/INFO]: Broadcasting shape cubic_parabola for RailPair[...] to 1 player(s) via N key(s)
[Netty Local Client IO #0/INFO]: [YanyangEasement] Received shape sync: cubic_parabola for RailPair[...] in minecraft:overworld
[Render thread/INFO]: [YanyangEasement] Client rail shapes changed; asking ANTE to rebuild baked rails
```

之后每 ~20 秒（400 tick）的诊断报告中 `byMirror` 应开始非零递增（客户端镜像开始工作），
`miss` 应逐步下降。
