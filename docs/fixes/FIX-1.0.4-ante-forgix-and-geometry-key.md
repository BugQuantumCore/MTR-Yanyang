# v1.0.4 修复说明：线型施印后完全不生效（ANTE 环境两个叠加根因）

## 现象

用户在 ANTE（MTR-ANTE 1.2.0-DEV-19，Aphrodite 社区分发版）+ YMTR 3.5.6 环境下，
用缓和曲线工具施印线型后**轨道外观毫无变化**（1.0.3 修复无效）。

日志（latest.log）铁证：

```
[22:07:50] Broadcasting shape cubic_parabola for RailPair[a=-1649268408380, b=-28732]  (节点 -7,-60,-237 <-> -1,-60,-8)
[22:07:50] Received shape sync: cubic_parabola ...     ← 客户端收到了
[22:07:50] Client shape mirror: ... -> cubic_parabola  ← 镜像更新了
[22:07:50] Client rail shapes changed; asking ANTE to rebuild baked rails  ← 请求重烘
[22:07:54] [server] Rail shape resolution: byStamp=2, byMirror=8, byAnteConfigs=0, classicExplicit=8, miss=40; lastMissPair=[824632754116, 824633692100]
```

对 `lastMissPair` 解码得 `(2,-60,-237) <-> (2,-60,-8)` —— 与施印轨道是**同一条**
（z/y 完全一致），**唯独 x 全错**（真实节点 x=-7/-1，几何推导出 x=2）。
即客户端渲染查询镜像时用的「几何推导键」与服务器广播的「节点键」对不上，
查询 miss → 回退经典线型。同时 `byAnteConfigs=0`，整个日志中**没有任何一条**
`ANTE integration: ... resolved` 消息 —— ANTE 集成反射全部静默失败。

## 根因（两个独立 bug 叠加）

### 根因 1：AnteMirror 反射的类名不带 Forgix 前缀（集成全灭）

Aphrodite 的 MTR-ANTE 使用 **Forgix 合并的多 loader jar**：Fabric 侧所有运行时
类名都带 `fabric.` 前缀（jar 内 `fabric/cn/zbx1425/mtrsteamloco/...`，
mixins.json 的 package 即 `fabric.cn.zbx1425.mtrsteamloco.mixin`）。

1.0.3 的 `AnteMirror` 用 `Class.forName("cn.zbx1425.mtrsteamloco.data.RailExtraSupplier")`
（无前缀）→ **ClassNotFoundException** → 且失败时**静默**（无日志）→ 以下通路全部
变成 no-op：

- `setRailCustomConfigsMirror`：施印时把 `rail_shape` 写入 ANTE customConfigs —— 失效
  （`byAnteConfigs=0` 的原因之一）；
- `readRailShapeKey`：从 customConfigs 读回线型的兜底 —— 失效；
- `resolveClearBakedRails`（`MainClient.railRenderDispatcher.clearRail`）：
  施印后请求 ANTE 重烘轨道 —— 失效。**即使镜像命中，视觉也不会更新。**

已用真实 MTR-ANTE 1.1.0 jar 验证：`fabric.cn.zbx1425.mtrsteamloco.data.RailExtraSupplier`
可解析且 `getCustomConfigs/setCustomConfigs` 签名匹配；无前缀类名不存在。

### 根因 2：镜像键 = 节点 BlockPos，客户端查询键 = 经典几何推导，两者不一致

`RailMixin.yanyang$pairKey` 按 MTR 经典公式（h/k/r/t 字段）从几何复原端点。
**被 ANTE 轨道编辑器重写过的 Rail，其几何字段不再满足 MTR 构造器的端点锚定
性质** —— 本例中一条 (-7,-237)→(-1,-8) 的南北长轨被推导到「x=2 的南北直线」
上（两端 x 恒为 2.x），镜像按节点键存、按几何键查 → 永远 miss。

（用上游 MTR 3.x 构造逻辑 + 全部 16×16 facing 组合模拟排除了「MTR 原生几何
产生该结果」的可能性 —— 原生构造的端点必然锚定在节点中心。）

## 修复（v1.0.4，三个层次）

### 1. AnteMirror：Forgix 前缀候选 + 不再静默（`AnteMirror.java`）

- `RailExtraSupplier` / `MainClient` 均按 **`fabric.` 前缀优先、无前缀兜底** 的顺序
  尝试两个候选类名（原版 NTE 用无前缀，Aphrodite MTR-ANTE 用前缀）；
- 解析成功打 INFO（含解析到的实际类名）；失败打 INFO 提示（不再无声无息）；
- catch 从 `ReflectiveOperationException` 加固为 `Throwable` —— 类存在但链接
  失败（LinkageError）时也不会让施印/同步流程崩溃；
- `clearRail` 仍通过 `dispatcher.getClass()` 动态解析，与具体类型无关。

### 2. 镜像双键广播 + 真实节点锚定（核心，`ShapeStore` / `RailMixin` / `ClassicRailGeometry` / `PacketTrainDataGuiServerMixin` / `YanyangEasementModClient` / `RailDuck`）

- **`RailDuck` 新增 `yanyang$pairKey()`**：把「从经典几何推导连接键」的逻辑暴露
  给服务器广播侧，**服务器与客户端用同一套推导** —— 无论几何是否被污染，两端
  推导结果必然一致；
- **`broadcastShape` 广播多重键**：节点键（服务器权威 BlockPos）+ forward/backward
  两个 Rail 对象的几何推导键，去重后逐键发送；`createRailS2C` mixin 与 join sync
  （`syncPlayerShapes`）都改为多键；
- **镜像条目升级为 `ShapeRecord(shapeKey, nodeA, nodeB)`**：S2C 协议在 shape 串后
  追加真实节点对（两个 long），客户端按收到的键存储；
- **端点锚定（`ClassicRailGeometry.resolveEasedAnchored`）**：客户端几何键命中后，
  若几何推导端点与真实节点不一致（几何被污染），把缓和曲线**两端强制锚定到
  真实节点**（按距离就近配对起终点）；方向角仍取几何切向（编辑器写入的朝向
  通常与节点连线一致）；几何正常时（推导端点 == 节点）行为与 1.0.3 **逐位一致**
  （单元测试验证采样表完全相同，无回归）。

### 3. 诊断增强

- `Broadcasting shape ... via N key(s)`、`Client shape mirror: ... nodes A <-> B`
  日志携带键数与节点信息；
- ANTE 集成解析失败时明确提示「如果安装了 ANTE/NTE 请报告此日志」。

## 验证

- **链接**：新 jar 全部 24 个类在 stubs classpath 上 `Class.forName` 链接通过；
- **单元测试**（复现用户场景）：21/21 通过 ——
  - 污染几何（x=2 南北直线 + 真实节点 -7/-1）推导键与节点键不一致（复现 miss）；
  - 双键广播同时覆盖两种键；
  - 锚定后缓和曲线端点精确落在 (-1,-8)/(-7,-237) 节点中心、长度 > 229、
    中间采样不超出节点范围（无「瞬移到 x=2」）；
  - 正常几何锚定前后**采样表完全一致**（无回归）；
  - classic（空串）语义不变；
- **AnteMirror 实测**（真实 MTR-ANTE 1.1.0 jar + fabric/ 前缀类路径）：
  `RailExtraSupplier resolved as fabric.cn.zbx1425.mtrsteamloco.data.RailExtraSupplier`，
  `getCustomConfigs/setCustomConfigs` 签名匹配；`MainClient.railRenderDispatcher`
  字段与 `RailRenderDispatcher.clearRail()` 方法存在；对非 ANTE 对象的读取安全
  返回 null，`clearBakedRails` 无异常逃逸；
- **Mixin 注解**：`@Mixin/@Shadow/@Unique/@Inject/@At` 以 CLASS 保留期完整写入
  字节码，refmap 维持空映射（全部 `remap = false`，与 1.0.2/1.0.3 一致）。

## 构建说明

基于 1.0.3 的类文件做**增量重编译**：`AnteMirror`、`RailDuck`、
`ClassicRailGeometry`、`ShapeStore`（含新内部类 `ShapeRecord`）、`RailMixin`、
`PacketTrainDataGuiServerMixin`、`YanyangEasementModClient` 七个（组）类被替换，
其余类保持 1.0.3 原始字节码。编译 classpath 为 MC(1.20.1-intermediary) +
YMTR + fabric-api 的签名 stub 集 + 1.0.3 jar。

## 测试建议（游戏内）

1. 进入存档，过滤日志 `YanyangEasement`：
   - 应看到 `ANTE integration: RailExtraSupplier resolved as fabric.cn...`
     （若仍显示 not found 请立即回报，附完整日志）；
2. 对**之前施印无效的那条 ANTE 编辑过的轨道**重新施印三次抛物线 → 外观应立即
   变为缓和曲线（日志 `byMirror` 增加、`miss` 不再增长）；
3. 施印后用 ANTE 刷子再编辑该轨道 → 线型应保持（1.0.3 的恢复链路 +
   1.0.4 的 customConfigs 通路双保险）；
4. 退出重进世界 → 线型应保持（存档印记 + join sync 多键同步）；
5. 原生 MTR 铺设的普通轨道（未经 ANTE 编辑）施印 → 行为与旧版一致。

## 兼容性说明

- S2C `shape_sync` 协议新增两个 long 字段（nodeA/nodeB），**客户端与服务器必须
  同时升级到 1.0.4**（单人存档自动满足；专用服务器请同步更新两端 mod）；
- 原版 NTE（非 Forgix 合并包）用户：无前缀候选兜底生效，行为与之前一致；
- 未安装 ANTE/NTE 的用户：所有集成通路保持 no-op，仅新增两条一次性 INFO 提示。
