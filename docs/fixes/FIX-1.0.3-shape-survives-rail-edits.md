# v1.0.3 修复说明：线型在轨道编辑后悄然失效

## 现象

用户用缓和曲线工具在节点上设置线型后**当时生效**，但随后在以下任一操作后，
该连接悄悄回退为经典「直线 + 圆弧」线型：

1. 用 ANTE/NTE 的**刷子（Brush Edit Rail）**或任何轨道编辑 GUI 修改该轨道
   （换模型、调竖曲线、加Roll角、改 custom configs 等）；
2. 用 MTR 自带的**轨道连接器**在已设线型的两节点之间重连 / 改轨型 / 改单向。

## 根因（对 YMTR 3.5.6 + ANTE 1.2.0-DEV-19 + 本补丁 1.0.2 反编译核实）

MTR 3.x 的轨道同步只走 `writePacket` / `Rail(FriendlyByteBuf)`，
**不携带 yanyang 印记**（印记只写入 msgpack 存档）。客户端因此只能靠
ShapeStore 的自定义 S2C 通道按「节点对」同步线型。这一层在 1.0.2 是好的。

真正的问题在于**服务器侧的轨道对象会被整体替换**：

- ANTE 的 `PacketUpdateRail.receiveUpdateC2S` 用客户端送来的新 `Rail`
  替换 `rails` 表中的双向对象，随后 `createRailS2C`；
- MTR 的 `ItemRailModifier.onConnect` 更是直接 `new Rail(...)` 两个全新对象
  存入 `rails` 再 `createRailS2C`。

替换后的新对象：

1. **印记为空**（writePacket 从不传输印记，存档也未重写）；
2. **ANTE customConfigs 来自客户端旧副本** —— 而客户端副本上根本没有
   `rail_shape`：工具施印时（`applyShapeViaStamp`）只镜像到**服务器**对象，
   从未调用 `createRailS2C`，客户端无从得知。

于是 1.0.2 在 `createRailS2C` TAIL 广播 `effectiveShapeKey(rail1)` 时读到
`null`，**主动广播「经典线型」**，客户端镜像被覆盖成 classic —— 线型就此丢失。
1.0.2 新增的 `AnteMirror.readRailShapeKey` 兜底正是为这条链路准备的，
但因为客户端副本从未持有过 `rail_shape`，这个兜底永远读不到东西。

## 修复（v1.0.3，三个互相配合的层次）

### 1. 施印后立即向客户端回推轨道（`EasementShapeApplier.applyShapeViaStamp`）

印记 + 镜像 customConfigs 之后，追加调用
`PacketTrainDataGuiServer.createRailS2C(...)`：

- 客户端用带 `rail_shape` 的新 Rail 对象替换本地副本；
- ANTE 依据其 equals（writePacket 字节，含 customConfigs）自动重烘该轨道；
- 此后用户再用刷子编辑时，客户端会把 `rail_shape` **原样带回服务器**，
  1.0.2 的读回兜底从此真正生效。

### 2. `createRailS2C` 时恢复线型（`PacketTrainDataGuiServerMixin`）

`effectiveShapeKey` 的解析顺序扩展为：

1. rail1 / rail2 上的印记；
2. rail1 / rail2 的 ANTE customConfigs（`rail_shape`）；
3. **服务器形状记忆**（见第 3 点）。

一旦解析出线型，`reapplyShape` 把印记 + customConfigs 镜像**重新套回**
参与本次 `createRailS2C` 的对象，以及 `rails` 表中当前存放的同连接对象
（防御调用方传副本的情况）。这覆盖：

- ANTE 刷子编辑（configs 随客户端带回）；
- MTR GUI 重连（全新对象，靠第 3 层记忆恢复）；
- 用户直接在 ANTE 配置界面手填 `rail_shape` 的用法（顺带修复）。

显式回退经典（工具循环到 classic）时记忆记录为空串，**不会**被误恢复。

### 3. 服务器形状记忆（`ShapeStore`）

新增按 (维度, 节点对) 的会话级记忆：

- 工具每次施印（含显式 classic）都记录；
- `createRailS2C` 解析成功后也记录；
- `ServerLifecycleEvents.SERVER_STOPPED` 清空 —— 单玩家换世界/换存档
  不会把 A 世界的形状误套到 B 世界。

`syncPlayerShapes`（加入/切维度）同样扩展为 印记 → configs → 记忆。

### 附带调整

- `EasementShapeApplier` 的 railData-capable 分支（YMTR 3.6.2.2+ 的
  `Rail#getRailData/setRailData`）改为反射调用 —— 编译不再依赖新版 YMTR，
  运行时行为不变（旧版上该分支本就不会执行）；
- 版本号 → 1.0.3。

## 构建说明

本版基于 1.0.2 的类文件做**增量重编译**：仅 `EasementShapeApplier`、
`ShapeStore`、`YanyangEasementMod`、`PacketTrainDataGuiServerMixin`
（及其内部类）被替换，其余类保持 1.0.2 原始字节码。

已验证：

- 注入处理器签名与 YMTR 3.5.6 `createRailS2C` 逐字节匹配（与 1.0.2 相同）；
- 四个类在 MC(1.20.1-intermediary) + YMTR 3.5.6 + fabric-api 0.92.12
  classpath 上链接通过；
- ShapeStore 记忆读写/清空/经典线型语义单测通过；
- Mixin 注解以 CLASS 保留期完整写入字节码，refmap 维持空（全部
  `remap = false`，与 1.0.2 一致）。

## 测试建议（游戏内）

1. 铺一段曲线轨道 → 工具设为三次抛物线 → 确认生效；
2. **用 ANTE 刷子编辑同一段轨道**（任意改动并保存）→ 线型应保持；
3. 用 MTR 轨道连接器重连同一对节点（改轨型/单向）→ 线型应保持；
4. 工具循环回经典 → 上述两种编辑后应保持经典；
5. 退出重进世界 → 线型应保持（存档印记）；
6. 日志过滤 `YanyangEasement`：施印、`Restored easement shape ... onto
   N rail object(s)`、Broadcasting 应按预期出现。
