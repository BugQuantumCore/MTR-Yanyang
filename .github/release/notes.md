中文说明 / English notes

**MTR-Yanyang 4.1.0-beta-2.yanyang.7** 本次更新：

**关闭光影瞬间崩溃（第二轮修复——找到真正的根因）**
- 症状：yanyang.6 之后，在游戏内切换 Beryl 光影（尤其关闭）时游戏仍然直接崩溃——依旧**没有 Minecraft 崩溃报告、没有 JVM hs_err**
- 本轮直接反编译了 **Beryl 0.2.0-alpha 的字节码与着色器**（RenderingPipeline 切换路径、按键/选项界面 mixin、entity.vsh / terrain.fsh 光照曲线），确认了真正的根因：**切换本身**远比 yanyang.6 闩锁掉的"原生挂接"更重——Beryl 在渲染线程的按键处理中当场替换 VulkanMod 的 MainPass 对象、回退地形顶点格式与构建器、并触发**全量地形重建**（setBlocksDirty）；若从 Beryl 的设置界面切换，还会**释放 Beryl 的全部 GPU 对象**（clearResources）。而 MTR 每帧的全部绘制负载（数千模型面、数百乘客模型、动态纹理上传）照常砸进这个过渡窗口；渲染器所有权真正切换时，甚至**在过渡帧里同步执行多秒阻塞的整套模型重载**——任一环节都足以让驱动层带走进程
- 修复：任何 Beryl 状态翻转后，MTR 打开 **60 帧过渡静默窗口**：模拟照常推进（列车不误点），但 MTR 的全部绘制（车辆、电梯、轨道、乘客、模型、计划绘制、动态纹理上传）在 Beryl 落定前全部丢弃并清空队列，随后自动恢复；所有权重建（Java GL ↔ 原生 Vulkan）改到**窗口结束后的第一帧**执行。切换光影时约一秒钟的"列车隐身"，取代一次硬崩溃

**光影下乘客发光（真正的根因）**
- 症状：yanyang.6 采样了真实光照，但站台上的乘客在 Beryl 光影下**仍然发光**
- 根因（本轮从 Beryl 的 entity.vsh 中确认）：Beryl 的实体着色器把**方块光坐标本身**变换为**平方暖色自发光**——`texColor × ((uv/256)² × 2) × 暖色调`，方块光 15 时约 **1.76 倍**暖色泛光。而 MTR 站台为了防刷怪**全域保持光照等级 15**——真实光照恰恰就是 15，等于直接喂给了自发光。隧道与白天场景已由 yanyang.6 修好；被灯光照亮的站台没有
- 修复：Beryl 驱动帧期间，喂给**每一个 MTR 绘制的人形**（AI 乘客：站台 / 步行 / 车内，真实乘车玩家，连同手机模型）的方块光分量被钳制在 **8**——约 0.5 倍的"灯下暖光"取代刺眼泛光；纯 VulkanMod 与 OpenGL 走原版光照贴图曲线，保持真实光照，外观完全不变。想更亮可调 `VulkanCompat.BERYL_HUMANOID_BLOCK_LIGHT_CAP`

**验证**
- 两条 CI 流水线在提交 d034f01 上全绿：Build 1.21.x（1.21.1 / 1.21.4 × Fabric / NeoForge 四目标编译）+ Native Vulkan OBJ pipeline（SPIR-V 编译、6 平台原生库、装配、内置 8 平台 natives 的模组构建）
- 修复基于 Beryl 0.2.0-alpha 实际字节码与着色器反编译实证，而非日志推测

加载器 / 版本：**1.21.1 与 1.21.4 × Fabric / NeoForge**。原生 Vulkan 管线仅在检测到 VulkanMod 时自动启用（Beryl 光影运行的会话除外），其余情况走原 OpenGL 路径。

---

**MTR-Yanyang 4.1.0-beta-2.yanyang.7**:

**Beryl toggle hard crash — second round, real root cause found**
- Symptom: after yanyang.6, toggling Beryl shaders in-game (especially off) still killed the game outright — still **no Minecraft crash report and no JVM hs_err**
- This round decompiled **Beryl 0.2.0-alpha's actual bytecode and shaders** (the `RenderingPipeline` toggle path, its keybind/options-screen mixins, and the `entity.vsh` / `terrain.fsh` lighting curves) instead of inferring from logs. The toggle itself is far heavier than the native attach yanyang.6 latched away: Beryl flips on the render thread's key handling — swapping VulkanMod's `MainPass` object, reverting the terrain vertex format/builder, and forcing a **full terrain rebuild** (`setBlocksDirty()`); toggled from its options screen it additionally **frees every Beryl GPU object** (`clearResources`). MTR kept submitting its full per-frame load straight into that window, and on a real owner flip even ran the **multi-second blocking model reload inside the transition frame** — any of which takes the driver down
- Fix: after any Beryl state flip, MTR opens a **60-frame transition grace window**: the simulation keeps ticking (trains stay on schedule) but every MTR draw — vehicles, lifts, rails, passengers, models, scheduled renders, dynamic-texture uploads — is dropped and its queues cleared until Beryl settles, then everything resumes automatically. The owner rebuild (Java GL ↔ native Vulkan) now fires on the **first frame after** the window. A ~1-second invisible-trains pause replaces a hard crash

**Passenger glow under shaders — real root cause**
- Symptom: yanyang.6 sampled the truthful light, but passengers at stations still glowed under Beryl
- Root cause (confirmed from Beryl's `entity.vsh`): Beryl's entity shader turns the **block-light coordinate itself** into a squared warm emission — `texColor × ((uv/256)² × 2) × warm`, about a **1.76×** warm bloom at block light 15. MTR platforms deliberately keep light level 15 everywhere (mob-proofing) — the truthful light IS 15, feeding the emission directly. Tunnels and daylight were already fixed; lit platforms were not
- Fix: while Beryl drives the frame, the block-light component fed to **every MTR-rendered humanoid** (AI passengers — platform, walking, on-board — plus real riding players, phone cuboid included) is clamped at **8**: a ~0.5× lamp-lit warm tint instead of a beacon. Plain VulkanMod and OpenGL keep the truthful value (vanilla lightmap curve), so nothing changes outside Beryl. Prefer brighter passengers? Raise `VulkanCompat.BERYL_HUMANOID_BLOCK_LIGHT_CAP`

**Verification**
- Both CI pipelines green on d034f01: Build 1.21.x (1.21.1 / 1.21.4 × Fabric / NeoForge) + Native Vulkan OBJ pipeline (SPIR-V, 6-platform native libs, assemble, build-mod with the 8-platform natives bundled)
- The fixes are grounded in decompiled Beryl 0.2.0-alpha evidence, not log-line inference

Loader / versions: **1.21.1 & 1.21.4 × Fabric / NeoForge**. The native Vulkan pipeline only enables itself when VulkanMod is detected (except in sessions where Beryl's shaders are driving the frame); everything else stays on the original OpenGL path.
