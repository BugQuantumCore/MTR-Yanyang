# v1.0.6 修复说明：线型施印后轨道外观毫无变化（mixin 注解保留期回归）

## 现象（latest.log 铁证）

1.0.5 启动崩溃已修复，游戏正常进入。日志显示：

```
[19:17:32] Yanyang easement initialized: YMTR mode = legacy stamp (3.4.4 ~ 3.6.1.2), MTR version = 1.20.1-3.5.6
[19:18:18] ANTE integration: RailExtraSupplier resolved as fabric.cn.zbx1425.mtrsteamloco.data.RailExtraSupplier
           (customConfigs mirror + rail_shape read-back active)   ← 1.0.4 的 Forgix 前缀修复，已生效
[19:18:18] Join sync for BugQuantumCore: 0 eased shape(s) among 4 rail connection(s)
[19:18:25] Shape tool at node -1, -60, -8: 1 of 1 connection(s) -> cubic_parabola   ← 施印成功
[19:18:25] [CHAT] 轨道外观立即更新；列车将在车辆段重新生成路径后沿新线型运行。
（随后在两个节点上循环施印 cubic_parabola / half_wave_sine / classic 共 12 次）
```

但**整条同步/渲染链路一行日志都没有**：

- 没有 `Broadcasting shape ... via N key(s)`（服务器广播）
- 没有 `Received shape sync` / `Client shape mirror`（客户端镜像）
- 没有 `[server] Rail shape resolution: byStamp=.., byMirror=..`（诊断报告 —— ~700 tick 的
  游戏时长内所有解析/miss 计数器始终为 0，说明客户端 RailMixin 的注入点一次都没被调用）
- 没有任何异常 / mixin 报错 / 崩溃

即：ANTE 集成反射（AnteMirror，普通类，非 mixin）工作正常；而 **1.0.4 增量重编译过的两个
mixin（`PacketTrainDataGuiServerMixin`、`RailMixin`）在游戏里"成功应用"却什么都没注入**。

## 根因：1.0.4 构建把 Mixin 注解 stub 的保留期改反了

真实 Mixin 库（fabric-loader 0.19.5 捆绑的 sponge-mixin 0.8.7）的注解保留期
（从 Maven Central 下载真实 jar，逐注解反汇编核实）：

| 注解 | 真实保留期 | 1.0.3 stub（游戏内验证有效） | 1.0.4/1.0.5 stub |
|---|---|---|---|
| `@Mixin` | **CLASS** | CLASS ✓ | CLASS ✓ |
| `@Shadow` | **RUNTIME** | RUNTIME ✓ | **CLASS ✗** |
| `@Unique` | **RUNTIME** | RUNTIME ✓ | **CLASS ✗** |
| `@Inject` | **RUNTIME** | RUNTIME ✓ | **CLASS ✗** |
| `@At` | **RUNTIME** | RUNTIME ✓ | **CLASS ✗** |
| `@Final` / `@Accessor` / `@Overwrite` 等其余 | RUNTIME | — | **CLASS ✗**（顺带修正） |

Mixin 转换器读取这些注解时**只读"运行时可见"（RuntimeVisibleAnnotations）列表**：

- `MixinPreProcessorStandard.prepareShadow / attachUniqueMethod / attachFields`
  → `Annotations.getVisible(...)`（仅 visible）；
- `InjectionInfo.getInjectorAnnotation` → `Annotations.getSingleVisible(...)`（仅 visible）。

因此 CLASS 保留期（RuntimeInvisibleAnnotations）的 `@Inject` 根本不被识别为注入器：
mixin 类本身仍能"应用"（`@Mixin` 是 CLASS 保留期、由字节码层读取，不受影响），但
`@Inject` 方法被当作无注解方法合并进目标类成为死代码、`@Shadow` 字段被当作普通字段
静默跳过 —— **不崩溃、不报错、不注入**。这与日志现象逐项吻合：

1. `PacketTrainDataGuiServerMixin` 死 → `createRailS2C` TAIL 广播不触发 → 客户端镜像永远为空；
2. `RailMixin` 死 → `Rail#getPosition/getLength/render/goodRadius` 不改写 → 外观永远是经典线型；
   解析/miss 计数器永不递增 → 诊断报告静默。
3. 其余 4 个 mixin（`RailDataCarrierMixin` / `RailPersistenceMixin` / `RailwayDataAccessor` /
   未启用的分支）是 1.0.3 原始字节码，注解保留期本来就是对的，所以存档持久化、
   rails 访问器等链路一切正常 —— 进一步把故障面精确锁定在两个 1.0.4 重编译的 mixin 上。

> 为什么 1.0.4/1.0.5 的构建期验证没拦住：当时的注解审计只检查"注解以 CLASS 保留期完整
> 写入字节码"（把 CLASS 当成了"与真实库一致"的目标形态 —— 方向恰好搞反），而链接测试、
> 冒烟测试、Y104Test 都不经过真实的 Mixin 转换器，自然发现不了。

## 修复（v1.0.6，最小改动面）

1. **修正 6 个注解 stub**（`workspace-105/stubs/org/spongepowered/asm/mixin/`）：
   `Shadow` / `Unique` / `Final` / `injection/At` / `injection/Inject` / `gen/Accessor`
   → `@Retention(RetentionPolicy.RUNTIME)`（`Mixin` / `Dynamic` 保持 CLASS，与真实库一致）；
2. **仅重编译受害的两个 mixin**（ecj，与 1.0.4/1.0.5 构建同参数 `-source 17 -target 17`，
   classpath = 修正后 stub 集 + 1.0.5 jar）：`PacketTrainDataGuiServerMixin.class`、
   `RailMixin.class`；
3. 打包为 `yanyang-easement-mtr3x-1.0.6.jar`：自 1.0.5 jar 复制全部条目（顺序与压缩保留），
   替换上述 2 个类，`fabric.mod.json` 版本号 1.0.5 → 1.0.6。**其余 23 个类逐字节不变**，
   mixins.json / refmap / 资源 / 协议零改动。

## 验证（全部通过）

1. **真实 Mixin 库差分实验**（决定性证据）：用 Maven Central 的
   `net.fabricmc:sponge-mixin:0.17.5+mixin.0.8.7`（与 loader 0.19.5 捆绑版同源）在
   classpath 上以反射探测两个 mixin 类的注解可见性 —— 反射可见性与 Mixin 转换器
   "只读 visible 注解"在语义上完全等价：
   - 旧 1.0.5 jar：`RailMixin @Inject=0 @Shadow=0 @Unique=0`，`PacketGuiMixin @Inject=0`
     → **注解对真实 Mixin 库不可见，mixin 静默死亡**（复现线上症状）；
   - 新 1.0.6 jar：`@Inject=4 @Shadow=17 @Unique=11`，`@Inject=1` → 全部可见，可正常注入；
   - 1.0.3 jar（游戏内验证过有效的版本）交叉核对：`@Inject=4 @Shadow=17 @Unique=10`
     （@Unique 少 1 是 1.0.4 有意新增的 `yanyang$resolveEasedAnchored`）—— **注解形态
     与 1.0.6 完全一致**。
2. **代码零回归**：新旧 `RailMixin` 逐方法指令 diff **完全一致**；
   `PacketTrainDataGuiServerMixin` 除 `<clinit>` 在方法表中的位置（纯外观差异）外
   逐指令一致 —— 1.0.6 与 1.0.5 的差别只有注解可见性属性。
3. **全量链接测试**：25/25 类在 stub classpath 上 `Class.forName` 通过；
4. **Y104Test 回归**：21/21 通过（1.0.4 的双键广播 + 端点锚定几何逻辑完好）；
5. **冒烟测试**（1.20.1 运行时形状 classpath）：`onInitializeClient` / `sendShape` /
   `clientLookup` 全部通过，END_CLIENT_TICK 监听器真实注册；
6. **字节一致性**：1.0.6 与 1.0.5 逐条目 diff，仅 2 个预期类文件 + 版本号差异；
7. **注解审计**：最终 jar 全部 6 个 mixin 类中 `@Inject/@Shadow/@Unique/@Accessor` 均
   visible=true、`@Mixin` 均 visible=false（与真实库保留期一致）。

## 安装

1. **删除** mods 目录里的 `yanyang-easement-mtr3x-1.0.5.jar`；
2. 放入 `yanyang-easement-mtr3x-1.0.6.jar`；
3. 其余 mod 与环境无需任何改动。

## 游戏内测试建议（对照日志 `YanyangEasement`）

1. 进入存档后对任意轨道施印三次抛物线，本次应能看到完整链路（1.0.5 全缺失的行）：
   - `Broadcasting shape cubic_parabola for ... to 1 player(s) via N key(s)`
   - `Client shape mirror: ... -> cubic_parabola`
   - `Client rail shapes changed; asking ANTE to rebuild baked rails`
   - ~20 秒后的 `[server] Rail shape resolution: ... byStamp=.., byMirror=.., miss=..`
     （`miss` 若仍大于 0 且 `byMirror=0`，请回报 —— 那将是全新的键匹配问题，
     但 1.0.4 的双键广播 + 端点锚定正是为它准备的，本次终于能真正跑到）；
2. 轨道外观应在施印瞬间变为缓和曲线（ANTE 重烘激活）；
3. 施印后用 ANTE 刷子再编辑、退出重进世界 → 线型应保持（1.0.3 的恢复链路 +
   1.0.4 的 customConfigs 通路，本次全部恢复工作能力）；
4. 若仍有问题，请附上新的 `latest.log`。

## 兼容性说明

- 与 1.0.4/1.0.5 的 S2C 协议完全一致（shape + nodeA + nodeB），无任何协议/依赖变更；
- 单人存档直接升级；专用服务器两侧任意组合 1.0.4+ 均可互通；
- 1.0.4 的几何修复（双键广播 + 端点锚定）与 1.0.5 的三处 stub 种类修复逻辑保持原样 ——
  它们从未真正在游戏里运行过，**1.0.6 是这些修复首次获得实机验证的版本**。
