# 晏阳缓和曲线补丁（MTR 3.x）· Yanyang Easement Rails for MTR 3.x

本分支（`3.x`）包含一个独立的 **MTR 3.x 运行时补丁模组**，将晏阳技术组在
[`tsc-1.21.x`](../tsc-1.21.x) 分支中为 MTR 4.1.x（Transport Simulation Core）开发的
两种轨道路径算法 —— **三次抛物线缓和曲线** 与 **半波正弦缓和曲线** —— 移植到
MTR 3.x 架构（参考实现：[Yomis-Minecraft-Transit-Railway](https://github.com/yomi-china/Yomis-Minecraft-Transit-Railway)，下称 YMTR）。

| | |
|---|---|
| 模组 ID | `yanyang_easement` |
| 目标游戏 | Minecraft 1.20.1（Fabric） |
| 目标模组 | YMTR（MTR 3.x，`mtr`）3.6.x；其他 3.x 小版本预计兼容¹ |
| 注入方式 | SpongePowered Mixin（不改 YMTR 任何代码 / 存档格式） |
| 许可 | MIT（算法移植自 MIT 许可的 TSC 代码） |

¹ `Rail` 的经典几何字段与查询方法自 MTR 3.0 起保持稳定；1.19.2 版 YMTR 使用同一份
common 源码，理论上同样可用但未测试。

---

## 两种线型是什么

MTR 3.x 原版把一条轨道的水平几何解成「直线 + 圆弧」至多两段，段与段之间曲率突变 ——
直线与圆弧直接相接，转角在接点处瞬间产生。本补丁引入的两种线型让**整条轨道成为一条
曲率连续的路径**，曲率从两端的零平滑过渡（缓和曲线 / 过渡曲线，transition curve），即
现实干线铁路的标准做法：

- **三次抛物线（cubic parabola）**：分段线性曲率 —— 「直—缓—圆—缓—直」序列，
  曲率线性爬升/回落，中间可保留一段圆弧平台；弯道足够温和时平台收缩为一点，
  成为纯双缓和曲线。
- **半波正弦（half-wave sine）**：以单个半波正弦作为曲率图，经典线型中最平滑 ——
  曲率处处无角点，连峰值处都光滑。

两族曲线无法表达的几何（转角超过 135°、终点折返到起点之后、数值求解不收敛）会自动
**回退到经典求解器**，缓和曲线因此永远是严格的可选增强，不会破坏既有轨道。

算法本体（牛顿定解、曲率剖面、采样表积分）与 `tsc-1.21.x` 分支的
`org.mtr.core.data.EasedRailPath` 完全一致，见
[`EasedRailPath.java`](src/main/java/com/bugquantumcore/yanyang/easement/EasedRailPath.java)
（纯数学、零 Minecraft 依赖，附单元测试）。

## 在游戏中使用

### 1. 缓和曲线工具（推荐）

1. `/give @s yanyang_easement:easement_shape_tool`（或在创造模式物品栏 MTR 核心页中找到「缓和曲线工具」）。
2. **右键轨道节点**：循环切换该节点上所有连接的线型 —— 经典 → 三次抛物线 → 半波正弦 → 经典……
   切换结果会显示在动作栏。
3. **潜行右键**：反向循环。
4. 在**车辆段重新生成路径**后，列车即沿新线型运行。

### 2. 轨道数据编辑器（YMTR 自带）

手持 YMTR 的「轨道数据编辑器」右键轨道节点，为某条轨道添加：

| 名称 | 类型 | 值 |
|---|---|---|
| `rail_shape` | `string` | `cubic_parabola` 或 `half_wave_sine` |

保存即可。删除该键（或填其他值）即回到经典线型。同一节点连接多条轨道时可用 ◀ ▶
按钮逐条编辑 —— 两种方式写入的数据格式完全一致，工具是「批量切换」，编辑器是「单条精调」。

### 数据格式与同步

线型标记存放在 YMTR 原生的**轨道附加数据**（`railData`）中，随存档持久化、随网络同步、
与 ANTE（MTRSteamLoco）的 `customConfigs` 自动镜像（可被 ANTE JS 读取）。因此本补丁
**不改动 MTR 的任何序列化格式**，卸载补丁后轨道仅回退为经典线型，数据无损。

## 实现原理

MTR 4.1.x（TSC）在构造 `RailMath` 时按 `Rail.Shape` 枚举求解缓和路径；MTR 3.x 的
`mtr.data.Rail` 则在构造函数中就把几何烘焙为经典分段字段（`h/k/r/tStart/tEnd` 等）。
本补丁对应地把生效点移到**几何查询时**（`RailMixin`，惰性构建 + 缓存）：

```text
Rail (mtr.data.Rail)
 ├── railData 含 rail_shape=cubic_parabola|half_wave_sine ?
 │     └── 是：从经典几何字段复原两节点坐标与 22.5° 网格方向角
 │            → EasedRailPath.build(...) 求解并缓存采样表
 │            → getPosition / getLength / render / goodRadius 全部改查采样表
 │            → 求解失败（不可表示几何）→ 自动回退经典分段
 │     └── 否：完全走原版逻辑（零开销：一次 Map 查询）
 └── setRailData（编辑器保存 / 工具切换）→ 缓存失效，下次查询重建
```

- **竖曲线（Y 剖面）** 沿用 YMTR 原版 `getPositionY`（含缆车垂度），只是定义域换成
  缓和后的总长度。
- **渲染** 按经典 `renderSegment` 的粒度（约每方块一段）与回调签名（四角 XZ + 两端 Y）
  发射四边形，轨道贴图密度与经典轨道一致；信号色带、单行线箭头等下游绘制自动跟随。
- **列车、信号、桥隧修建、路径生成** 都经由 `getPosition`/`getLength` 消费几何，因此
  全部自动沿缓和曲线工作 —— 与 TSC 分支中「所有下游消费同一张采样表」的设计相同。
- **线程与构造顺序**：反序列化构造函数在给 `facingStart`/`facingEnd` 赋值时就会触发
  首次几何查询，此时字段尚未赋值 —— 因此缓和路径的方向角不读字段，而是按
  `getRailAngle` 的原逻辑从经典几何重新推导并量化到 22.5° 网格；缓和路径端点方向角
  由构造精确等于请求值，两种触发顺序结果一致，`isValid()` 校验始终成立。
- **注入目标全部按方法名匹配**（`remap = false`、不写描述符）：目标方法均为模组方法
  （生产环境不参与混淆重映射），而描述符中的 Minecraft 类型在开发/生产环境分别使用
  命名/中间映射名 —— 按名匹配可同时适配两种环境。

与上游 tsc-1.21.x 的差异汇总：

| | tsc-1.21.x（TSC / MTR 4.1.x） | 本补丁（MTR 3.x） |
|---|---|---|
| 线型载体 | `Rail.Shape` 枚举新增两个值 | `railData` 键值对（`rail_shape`） |
| 生效时机 | 构造 `RailMath` 时求解 | `Rail` 几何查询时惰性求解（Mixin） |
| 端点/方向角来源 | 构造入参 `Position`/`Angle` | 由经典求解器几何复原（含 22.5° 量化） |
| 竖曲线 | `RailMath.getPositionY`（形状感知） | YMTR 原版 `getPositionY`（阴影调用） |
| 渲染回调 | 13 参数（含 Y 与倾斜角） | 10 参数（YMTR `RenderRail` 签名） |
| 修改范围 | 修改 TSC 源码 | 零源码修改，纯运行时注入 |

## 构建

依赖：JDK 17；另需将 YMTR 的**开发环境 jar**（Mojang 命名）放入 `libs/`
（获取方式见 [`libs/README.md`](libs/README.md)）。

```bash
./gradlew build        # 产物位于 build/libs/
./gradlew test         # 运行 EasedRailPath 纯数学单元测试
```

> 网络受限环境可在 `settings.gradle` 的 `pluginManagement` 中加入
> `https://bmclapi2.bangbang93.com/maven`（Fabric Maven 镜像），并视需要将
> `gradle/wrapper/gradle-wrapper.properties` 中的分发地址换为镜像。

## 目录结构

```text
src/main/java/com/bugquantumcore/yanyang/easement/
├── EasedRailPath.java        # 算法移植（tsc-1.21.x org.mtr.core.data.EasedRailPath，纯数学）
├── ClassicRailGeometry.java  # 经典几何快照 → 复原节点/方向角 → 求解缓和路径（纯数学）
├── EasementShape.java        # 线型枚举与 railData 键值解析
├── EasementShapeApplier.java # 服务端：写入 railData 并按 YMTR 流程广播同步
├── YanyangEasementMod.java   # Fabric 入口：注册物品
├── item/ItemEasementShapeTool.java   # 缓和曲线工具（右键节点循环切换线型）
└── mixin/RailMixin.java      # 核心注入：getPosition/getLength/render/goodRadius/setRailData

src/test/java/com/bugquantumcore/yanyang/easement/
├── EasedRailPathTest.java        # 算法单元测试（端点锚定、零端点曲率、S 弯、回退……）
└── ClassicRailGeometryTest.java  # 复原链路端到端测试（直线/斜线/四分之一圆弧）
```

## 注意事项

- 切换线型后需在**车辆段重新生成路径**，列车才会沿新线型行驶（与 MTR 修改任何轨道
  属性后的流程一致）；轨道渲染与桥隧修建立即生效。
- 平台/侧线容量按建轨时的长度记录，切换线型后不追溯调整（数据层语义，无害）。
- 极小半径的急弯在两族缓和曲线下可能不可表示，此时自动回退经典线型（动作栏不会
  报错，轨道表现为经典形状）。
- 缓和曲线不改变轨道的连接关系、信号闭塞与限速 —— 它只是另一种「铺轨的几何形状」。

## 致谢

- 算法源自 [MTR-Yanyang](https://github.com/BugQuantumCore/MTR-Yanyang) 仓库
  `tsc-1.21.x` 分支的 `EasedRailPath`（基于 Jonathan Ho 的
  [Minecraft Transit Railway](https://github.com/jonafanho/Minecraft-Transit-Railway)
  / Transport Simulation Core，MIT）。
- MTR 3.x 目标平台为 [Yomis-Minecraft-Transit-Railway](https://github.com/yomi-china/Yomis-Minecraft-Transit-Railway)。
