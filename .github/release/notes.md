中文说明 / English notes

**MTR-Yanyang 4.1.0-beta-2.yanyang.6** 本次更新：

**关闭光影时游戏直接崩溃（严重修复）**
- 症状：开着 Beryl 光影进入世界后，在游戏内关闭光影的瞬间游戏直接崩溃——**既没有 Minecraft 崩溃报告，也没有 JVM hs_err 崩溃日志**
- 根因：旧逻辑在检测到 Beryl 光影激活时**不闩锁**原生 Vulkan 管线的引导开关（每帧重试，等待"光影关闭的那一刻"）。于是关闭光影后的第一帧，原生录制器就在 VulkanMod 的渲染通道与命令缓冲仍在 Beryl 交接过渡的当口**中途挂接**到正在活动的 Vulkan 设备上——驱动层直接把进程带走（这正是无崩溃报告的原因：JVM 根本来不及反应）
- 修复：**Beryl 光影运行期间，原生管线引导在整个会话内闩锁关闭**——中途挂接从此不可能发生。光影开启进入的会话，关闭光影后继续由 Java 渲染器绘制（安全且被 Beryl 正确着色）；断开重连（关闭光影状态）后照常干净引导原生管线；纯 VulkanMod（无光影）行为完全不变
- 附带：光影开/关切换不再一律触发整套模型重建（多秒卡顿）——只在**渲染器所有权真正切换**（Java GL ↔ 原生 Vulkan）时重建；JDK 22+ 共享内存传输下每次引导日志里那个吓人的 `wr_shutdown downcall unavailable` NPE 也已消除

**AI 乘客在光影下发光（修复）**
- 症状：Beryl 光影下，车厢里的 AI 乘客（以及真实玩家）明显**发光**——隧道里、夜晚尤其刺眼，而车厢本身在正确变暗
- 根因：车厢内乘客一直以**恒定全亮光照**（`DEFAULT_LIGHT` = 0xF000F0）绘制。原版渲染器把它显示成"始终平亮"，但光影管线会把最大光照贴图坐标当作**自发光**处理
- 修复：乘客与玩家现在按各自实际位置采样世界光照（位置 +1 Y，方块光+天空光打包），与站台/步行乘客的取光方式完全一致——光影下不再发光，原版下夜晚/隧道中乘客也会正确随环境变暗

**验证**
- 两条 CI 流水线在提交 d4849c7 上全绿：Build 1.21.x（1.21.1/1.21.4 × Fabric/NeoForge 四目标编译）+ Native Vulkan OBJ pipeline（SPIR-V 编译、6 平台原生库、装配、内置 8 平台的模组构建）

加载器 / 版本：**1.21.1 与 1.21.4 × Fabric / NeoForge**。原生 Vulkan 管线仅在检测到 VulkanMod 时自动启用（Beryl 光影运行的会话除外），其余情况走原 OpenGL 路径。

---

**MTR-Yanyang 4.1.0-beta-2.yanyang.6**:

**Hard crash when turning the shaders off (critical fix)**
- Symptom: with Beryl shaders on in-game, the exact moment you toggled them off the game died outright — **no Minecraft crash report and no JVM hs_err log**
- Root cause: the old logic deliberately did NOT latch the native Vulkan boot when Beryl was detected (it retried every frame, "waiting for the moment the shaders go off"). So the first frame after the toggle attached the native recorder MID-SESSION onto VulkanMod's live device, in the middle of Beryl's render-pass/command-buffer handover — the driver took the whole process down before the JVM could react
- Fix: **while Beryl's shaders are driving the frame, the native boot is latched off for the entire session** — a mid-session attach can never happen again. A session that started with shaders on keeps the safe Java renderer after you toggle them off; disconnecting and rejoining (with shaders off) boots the native pipeline cleanly as always; plain VulkanMod behaviour is unchanged
- Bonus: a Beryl toggle no longer always triggers the multi-second full model rebuild — it now fires only on a real **renderer-owner transition** (Java GL ↔ native Vulkan); and the scary `wr_shutdown downcall unavailable` NPE in the log on every JDK 22+ shared-memory boot is gone

**AI passengers glowing under shaders (fix)**
- Symptom: under Beryl, on-board AI passengers (and real players) visibly **glowed** — worst in tunnels and at night — while the car body around them dimmed correctly
- Root cause: riders inside vehicles were drawn with a constant **full-bright lightmap** (`DEFAULT_LIGHT` = 0xF000F0). Vanilla shows that as flat lighting; shader pipelines read max lightmap coordinates as **emissive**
- Fix: riders now sample the packed world light (block + sky) at their actual position (+1 Y), exactly like the platform/walking passengers — no more glow under shaders, and they dim with the environment in vanilla too

**Verification**
- Both CI pipelines green on commit d4849c7: Build 1.21.x (all four 1.21.1/1.21.4 × Fabric/NeoForge targets) + Native Vulkan OBJ pipeline (SPIR-V, 6 platform natives, assemble, build-mod with all 8 platforms embedded)
