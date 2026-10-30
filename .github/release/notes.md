中文说明 / English notes

**MTR-Yanyang 4.1.0-beta-2.yanyang.10** 本次更新：

**"两个新增的轨道路径算法未对实际路径产生任何影响"——根因找到并修复（缓和曲线的角度语义反了）**
- 根因（本轮实证）：TSC 存储轨道角度遵循一套"朝向"约定——铺轨时 `Rail.getAngles` 归一化后，**angle1 从节点 1 指向节点 2，而 angle2 是从节点 2 指回节点 1**。经典分段求解器内部会先做 `getOpposite()` 矫正再解算；yanyang.9 的缓和曲线求解器 `EasedRailPath` **漏了这一步**，直接把存储的 angle2 当作"到达方向"
- 后果：几乎所有真实轨道算出的转角接近 180°，被预解算护栏**静默拒绝**、回退经典求解器——渲染出来的轨道和原来**一个字节都不差**，这就是"毫无效果"的真相；少数漏过护栏的几何（90°、45° 转角）更糟——解出**两倍长度的回头弯**（90° 弯解出 14.9 格的怪曲线，正确值 8.2 格）
- 修复：缓和曲线求解器现在用 `angle2.getOpposite()` 作为节点 2 的行进方向（并写进采样表的终点朝向）。用真实 TSC 源码（RailMath / EasedRailPath / Angle）编译的独立测试环境按存储语义逐类验证：直线走直线表、S 弯按真实长度求解、**22.5°–135° 转角全部成功求解**（90° 弯 8.23 格、相对经典解的偏差 0.379 格——肉眼可见的缓和外伸）；**同一条轨道无论从哪个方向铺设，几何完全一致**；只有真正的大于 135° 掉头弯仍回退经典解算器（设计如此）
- 切换 Shape 后保存即生效：渲染、列车行进、碰撞与半径查询共用同一张采样表——**列车严格沿显示轨道行进**

**"轨道在手持刷子时显示的类型颜色异常发光"——修复**
- 手持刷子/连接器时出现的轨道类型颜色、单行箭头、信号虚线、节点标记、轨道统计文字，此前全部以 `pack(15, 15)` 全亮光照绘制；Beryl 光影下方块光分量全额喂进平方暖色自发光项（约 1.76 倍）——类型颜色看起来在"发光"
- 全部 7 处绘制点现在接入 `VulkanCompat.capBlockLightForBeryl`（与 yanyang.7 乘客钳制同一方案）：天空光 15 保证昼夜可见性，方块光分量被钳制——Beryl 把它们渲染成"被照亮"而不是"自发光"。非 Beryl 渲染路径零变化

**启动日志五类报错全部处理**
- **60 条 Log4j ClassCastException（每次启动）**：TSC 打包了一份整体重定位的 log4j（`org.mtr.libraries.org.apache.logging.log4j`），但 Log4j2 插件缓存里仍是**原始类名**——游戏内被遮蔽的 Interpolator 按原始名解析到了 Minecraft 自带的原版 log4j 类，每个 LoggerContext 初始化时跨体系 `asSubclass` 失败（15 个 lookup × 4 个上下文）。TSC 的 shadow jar 现在**完全不再打包 log4j**（api/core/slf4j-impl 全部排除、引用保持原包名）——TSC 自己的日志直接绑定游戏宿主日志器，消息正常进 `latest.log`，堆栈刷屏消失
- **`mtr:textures/block/transparent.png` Invalid model!**：这是 MTR 自己的模型类型检查在**某个自定义资源包条目**（模型路径既不是 `.bbmodel` 也不是 `.obj`）上触发——不是 mod 本体数据。报错现在会**同时打印出问题的模型资源路径**，一眼定位坏资源包
- **3 条矿车声音缺失**：`sounds.json` 还声明着 `minecart_loop` / `minecart_motor3` / `minecart_motor4`，但对应的 ogg 文件早已不在 jar 里。死条目已删除（其余矿车事件 `doorcls` / `dooropn` / `point` / `run` 的文件都在）
- **blocks 图集 mipmap 4→2 降级**：72×36（和 272×216）的 PSD/APG 门贴图通过"仅粒子"的占位模型（`psd_door_1` / `apg_door`——门体本身是动态渲染的）混进了方块图集。它们的粒子现在引用 16×16 的 transparent 贴图；对全部 384 个被 blockstate 引用的模型做完整审计后确认**不再有任何低于 mip-4 要求的贴图**——图集恢复完整 mipmap 质量，**全游戏所有方块的远处渲染都更清晰**，不只是 MTR 的
- **1 条乘客皮肤请求超时**：Mojang 档案请求现在会在节流窗口内**自动重试一次**再回退到缓存皮肤（瞬时连接抖动不再直接打 WARN）

---

**English notes**

**"The two new rail path algorithms had no effect on the actual path" — root cause found and fixed (the easement solver read the stored angle2 backwards)**
- Root cause (proven this round): TSC stores rail angles in a facing convention — after the `Rail.getAngles` normalisation applied at rail creation, `angle1` faces from node 1 toward node 2, but `angle2` faces **back from node 2 toward node 1**. The classic segment solver corrects for this internally with `getOpposite()` passes before solving; the yanyang.9 easement solver `EasedRailPath` did not, and read the stored angle2 as the *arrival* heading.
- Consequence: nearly every real rail computed a heading change near 180° and was **silently rejected** by the presolve guard, falling back to the classic solver — rendering bit-identical to the existing track, i.e. "no effect". The geometries that slipped past the guard (90°/45° corners) were worse: they solved **hairpins twice the true length** (a 90° corner solved a 14.9-block monster instead of 8.2).
- Fix: the easement solver now uses `angle2.getOpposite()` as the node-2 travel heading (and writes it into the sample table's end heading). Verified in a standalone harness compiling the real TSC sources against stored-semantics geometry: straights take the straight table, S-bends solve at true length, **all 22.5°–135° bends solve** (90° corner 8.23 blocks, 0.379-block easement bulge vs classic — clearly visible), **both lay orders of the same physical track produce identical geometry**, and only genuine >135° hairpins still fall back (by design). Switching Shape and saving takes effect immediately; rendering, vehicles, bounds and radius queries share one sample table — trains follow the displayed track exactly.

**"Rail type colours glow while holding the brush" — fixed**
- The rail type tint, one-way arrows, signal dotted lines, node markers and rail statistics text all drew with `pack(15, 15)`; under Beryl the block half feeds the squared warm emission (~1.76×). All 7 draw sites now pass through `VulkanCompat.capBlockLightForBeryl` (the same clamp as the yanyang.7 passenger fix): sky 15 keeps them visible day and night while the block component is capped — lit, not emissive. Non-Beryl renderers untouched.

**All five reported boot-log error classes addressed**
- **60× Log4j ClassCastException per boot**: the TSC jar shipped a fully relocated log4j whose Log4j2Plugins cache keeps *original* class names; inside Minecraft the shaded Interpolator resolved those against the game's own unrelocated log4j classes and `asSubclass` cross-failed at every LoggerContext init (15 lookups × 4 contexts). The TSC shadow jar no longer ships log4j at all — TSC's own logging now binds to the Minecraft host logger and lands in `latest.log` properly tagged.
- **`mtr:textures/block/transparent.png` Invalid model!**: MTR's own model-type check firing on a custom resource pack entry whose model path is neither `.bbmodel` nor `.obj` — not mod data. The error now also logs the offending model resource path.
- **3× missing minecart sounds**: dead `sounds.json` entries for ogg files long removed from the jar are gone (the remaining minecart events all have their files).
- **Blocks atlas mip 4→2 downgrade**: 72×36 (and 272×216) door sprites reached the atlas through particle-only placeholder models; their particles now reference the 16×16 transparent texture, and a full audit of all 384 blockstate-referenced models confirms **no texture below the mip-4 requirement remains** — full mipmap quality for every block in the distance, not just MTR's.
- **1× passenger skin HttpTimeoutException**: the Mojang profile fetch retries once inside the request-throttle window before falling back to the cached skin.
