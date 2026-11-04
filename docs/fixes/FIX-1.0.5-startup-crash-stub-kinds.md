# v1.0.5 修复说明：游戏无法启动（crash: IncompatibleClassChangeError @ YanyangEasementModClient）

## 现象

安装 1.0.4 后游戏直接崩溃在客户端初始化阶段，无法进入主菜单。

```
java.lang.RuntimeException: Could not execute entrypoint stage 'client' due to errors,
  provided by 'yanyang_easement' at '...client.YanyangEasementModClient'
Caused by: java.lang.IncompatibleClassChangeError:
  Found class net.fabricmc.fabric.api.event.Event, but interface was expected
        at ...client.YanyangEasementModClient.onInitializeClient(YanyangEasementModClient.java:39)
```

## 根因：1.0.4 构建时签名 stub 集建错了三个类型的「种类」（class vs interface）

1.0.4 是用「stub classpath + 增量重编译」构建的。对比 1.0.3（游戏内运行成功）与 1.0.4
的字节码发现，当次构建重新生成的 stub 集里有三处类型种类/签名与真实运行时不符，
共毒化了 1.0.4 重编译的两个类（`YanyangEasementModClient`、`ShapeStore`）：

### 根因 1：`net.fabricmc.fabric.api.event.Event` stub 写成了 interface（→ 启动即崩）

用户环境 Fabric API 0.92.12+1.20.1（fabric-api-base 0.4.32）中 `Event` 是 **final class**
（直到 1.20.5+ 的新版 Fabric API 才改为 interface）。stub 写成 interface 后，
`ClientTickEvents.END_CLIENT_TICK.register(...)` 被编译成 `invokeinterface`，
运行时 JVM 对 class 执行 invokeinterface → **IncompatibleClassChangeError**，
即 crash report 中的直接死因。1.0.3 同一位置是 `invokevirtual`（正确）。

### 根因 2：`class_2540.writeLong` stub 用了 Yarn/Mojmap 签名（→ 首次形状同步即崩）

stub 曾声明 `public class_2540 writeLong(long)`。但在 intermediary 运行时，
class_2540（PacketByteBuf）自己的 writeLong 重写被混淆成 `method_XXXX(J)Lclass_2540;`，
名字 "writeLong" 只存在于**不被混淆的父类** `io.netty.buffer.ByteBuf`，签名是
`writeLong(J)Lio/netty/buffer/ByteBuf;`。于是 1.0.4 的
`invokevirtual class_2540.writeLong(J)Lclass_2540;` 运行时**无法解析 → NoSuchMethodError**
（施印/加入世界触发 sendShape 时）。1.0.3 是 `(J)Lio/netty/buffer/ByteBuf;`（正确）。

### 根因 3：`net.fabricmc.loader.api.FabricLoader` stub 写成了 class（→ 线型不生效会复发！）

用户环境 fabric-loader **0.19.5** 中 `FabricLoader` 是 **interface**（带 static `getInstance()`）。
铁证：1.0.3 的 `YanyangEasementMod`/`YanyangCompat` 用 `invokestatic`(InterfaceMethodref)
调 `getInstance()`、`invokeinterface` 调 `getModContainer(...)` 并在用户日志 18:18:26
完整执行成功（"MTR version = 1.20.1-3.5.6" 只能从 getModContainer 取到；
invokeinterface 在非 interface 上必然 ICCE）。

1.0.4 的 stub 把它写成 class，`ShapeStore.clientLookup` 被编译成
`invokestatic`(Methodref) + `invokevirtual getEnvironmentType` —— 在 loader 0.19.5 上
**抛 ICCE，客户端镜像查询全部失败 → 即使修好启动崩溃，「线型不生效」原症状也会原样复发**。
1.0.3 是 `invokestatic*` + `invokeinterface*`（正确）。

## 修复（v1.0.5）

修正三处 stub 后，仅重编译受毒化的两组类（`ShapeStore` 及内部类、
`YanyangEasementModClient`），替换进 jar；其余 21 个类保持 1.0.4 原始字节码
（其中 18 个与游戏内已验证的 1.0.3 逐字节相同），改动面最小化：

| 位置 | 1.0.4（错） | 1.0.5（对，= 1.0.3 已验证形态） |
|---|---|---|
| 客户端入口 `Event.register` | `invokeinterface` | `invokevirtual` |
| `sendShape` 的 `writeLong` | `(J)Lclass_2540;` | `(J)Lio/netty/buffer/ByteBuf;` |
| `clientLookup` 的 `FabricLoader.getInstance()` | `invokestatic`(Methodref) | `invokestatic`(InterfaceMethodref) |
| `clientLookup` 的 `getEnvironmentType` | `invokevirtual` | `invokeinterface` |

stub 修正明细（备份在 `yanyang-1.0.5-workspace-backup.tar.gz`）：
- `stubs/net/fabricmc/fabric/api/event/Event.java`：interface → **class**（含记录用 register）
- `stubs/net/minecraft/class_2540.java`：删除映射风格的 `writeLong`，改为声明
  `io.netty.buffer.ByteBuf writeLong(long)`（经父类 ByteBuf 解析，与运行时一致）
- `stubs/net/fabricmc/loader/api/FabricLoader.java`：class → **interface**（static getInstance）
- 补齐 `stubs/net/minecraft/class_7922.java`（class_2378 子类型）并把
  `class_7923.field_41178` 的擦除类型对准 `class_7922`（与真实运行时一致，
  供链接测试与未来增量重编译使用）

## 验证（构建期，全部通过）

1. **差分冒烟测试**（stub classpath 精确模拟用户运行时形状：Event=class、
   loader=interface、class_2540 无 "writeLong"）：
   - 旧 1.0.4 类：`onInitializeClient` 抛出与 crash report **逐字相同** 的
     `IncompatibleClassChangeError: Found class ...Event, but interface was expected`；
     `sendShape` 抛 `NoSuchMethodError: writeLong`；`clientLookup` 抛
     `IncompatibleClassChangeError: ...must be InterfaceMethodref constant` —— 三个 bug 全部复现；
   - 新 1.0.5 类：三条路径全部通过，END_CLIENT_TICK 监听器真实注册成功；
2. **全量链接测试**：最终 jar 全部 25 个类在修正后的 stub classpath 上 `Class.forName` 通过；
3. **回归测试**：Y104Test 21/21 通过（1.0.4 的双键广播 + 端点锚定几何修复无回归）；
4. **字节码审计**：最终 jar 25 类中——`invokeinterface Event` 0 处、错误 `writeLong` 描述符
   0 处、`invokevirtual FabricLoader` 0 处；正确形态 `invokevirtual Event.register` ×7、
   `writeLong(J)Lio/netty/buffer/ByteBuf;` ×4、`invokeinterface getEnvironmentType` ×1；
   与 1.0.4 的引用级 diff 仅含上述 4 处预期差异；
5. **完整性**：21 个未重编译类与 1.0.4 逐字节相同；jar 无损；`fabric.mod.json`
   版本 1.0.5、其余字段未动。

## 安装

1. **删除** mods 目录里的 `yanyang-easement-mtr3x-1.0.4.jar`（坏的，别留）；
2. 放入 `yanyang-easement-mtr3x-1.0.5.jar`；
3. 其余 mod 与环境无需任何改动。

## 游戏内测试建议

1. 启动游戏 → 应能进入主菜单（原崩溃点在客户端入口，日志过滤 `YanyangEasement`
   应出现 `Yanyang easement initialized: ...`，不再有 IncompatibleClassChangeError）；
2. 进入存档 → 对之前施印无效的 ANTE 编辑过的轨道重新施印三次抛物线 →
   外观应立即变化（1.0.4 的双键广播 + 端点锚定逻辑本次未改动，经 21 项回归测试确认）；
3. 施印后用 ANTE 刷子再编辑、退出重进世界 → 线型应保持；
4. 若仍有问题，请附上新的 `latest.log`（重点看 `YanyangEasement` 与
   `Rail shape resolution` 相关行）。

## 兼容性说明

- 与 1.0.4 的 S2C 协议完全一致（shape + nodeA + nodeB），无新协议变更；
- fabric.mod.json 依赖声明未变（minecraft 1.20~1.20.1、fabric-api *、mtr >=1.20.1-3.4.4）；
- 「线型不生效」的 1.0.4 几何修复（双键广播 + 端点锚定）逻辑保持原样 —— 1.0.4 从未真正
  跑起来过，这些逻辑的首次实机验证将在 1.0.5 完成。
