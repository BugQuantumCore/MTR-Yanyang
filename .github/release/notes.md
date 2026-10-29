中文说明 / English notes

**MTR-Yanyang 4.1.0-beta-2.yanyang.8** 本次更新：

**光影下"异常发光"的真正大头——列车与全部静态模型的烘焙光照（本轮根因修复）**
- 症状：yanyang.7 之后，乘客不再像灯泡了，但**整列列车（以及门、轨道、站牌、PIDS、电梯）在夜晚和站台里仍然泛着暖光**
- 根因（本轮从 Beryl 0.2.0-alpha 的 `entity.vsh`/`entity.fsh` 与原版 1.21.1 `LightTexture.pack` 字节码实证）：每一个 MTR 静态模型（`NewOptimizedModel`）在构建时把 **UV2 光照图坐标一次性烘焙进静态 GPU 顶点缓冲**，烘焙值一直是纯 GL 路径的全亮常量 `pack(15, 15)`。而 Beryl 的实体着色器把这个**冻住的方块光分量**直接当**平方暖色自发光**：`纹理 × (240/256)² × 2 × (1 − lightY) × 暖色调 ≈ 1.55 倍`——恰恰在 `lightY` 塌陷的夜晚、带顶棚的站台里最亮。**整列车的暖光就是从这一行烘焙里来的**
- 修复：Beryl 拥有 GL 模型期间，烘焙坐标改为 `pack(0, 15)`——**方块光 0 消灭自发光项**；**天空光 15 保住 Beryl 自己的环境光、GGX 直射光、阴影贴图遮蔽与雾效**（连天空光也清零会关掉 Beryl 的直射光闸门，只剩最低环境光，模型变成无光影剪影）。纯 GL、纯 VulkanMod、原生管线的烘焙保持全亮常量——**MTR 一贯的卡通风观感在这些路径下完全不变**
- 同时：Beryl 驱动帧期间，静态模型的逐次绘制亮度（ColorModulator）固定为 1——旧公式在完全黑暗场景恰好等于 0，会把 Beryl 光影刚算好的光照整帧乘没，留下纯黑剪影。光影包负责打光，颜色调制器保持中性。（GL / VulkanMod 路径公式原样保留）
- 乘客方块光钳制从 8 收紧到 **4**：8 在实测场景仍带约 0.5 倍暖光晕，4 只剩约 0.12 倍的"灯下微光"

**粒子发光——确认是 Beryl 自身行为，MTR 无法修复**
- 你观察到粒子也异常发光——正确：本轮反编译确认 Beryl 的 `particle.vsh` 携带**完全相同的平方自发光公式**，而原版给粒子填的是真实方块光。光源等级 10+ 附近的粒子就会泛暖光——这是光影包自己的设计，在 MTR 的管辖之外

**AI 乘客大小 / 手机姿势——已对照原版字节码逐项验证，未发现缺陷**
- 乘客变换链已对照**刚反编译的原版 1.21.1 `LivingEntityRenderer.render` 字节码**重新推导：MTR 链（年龄缩放在最外层 → 翻转 → 0.9375 → −1.501 → YP180）与原版链（`getAgeScale()` 缩放 → YP(180−yaw) → 翻转 → 缩放 → −1.501）**数学上完全等价**——0.9375 正是原版 `Player.getAgeScale()` 的返回值，链上每个元素（对角缩放、Y 轴 180° 旋转、Y 平移）两两可交换，儿童的脚和原版幼年生物一样贴地。手机枢轴 `(0, 8.2, −7.6)` 与 −50° 手臂姿势的手部会合点偏差小于 0.05 格
- **请求**：如果关闭光影（原生 Vulkan 管线）时仍能看到大小 / 手机姿势异常，那走的是另一条渲染管线——请发来那个场景的日志，将在原生路径单独修复

**验证**
- 两条 CI 流水线在提交 cb2874c 上运行：Build 1.21.x（1.21.1 / 1.21.4 × Fabric / NeoForge 四目标编译）+ Native Vulkan OBJ pipeline（SPIR-V、6 平台原生库、装配、内置 8 平台 natives 的模组构建）
- 全部结论基于 Beryl 0.2.0-alpha `entity.vsh`/`entity.fsh`/`particle.vsh`、VulkanMod 0.6.7 `VBO.drawWithShader` 与原版 1.21.1 `LightTexture.pack`/`LivingEntityRenderer` 的实际反编译字节码，而非日志推测

加载器 / 版本：**1.21.1 与 1.21.4 × Fabric / NeoForge**。原生 Vulkan 管线仅在检测到 VulkanMod 时自动启用（Beryl 光影运行的会话除外），其余情况走原 OpenGL 路径。

---

**MTR-Yanyang 4.1.0-beta-2.yanyang.8**:

**The real remaining "glow" under shaders — the baked model lightmap (this round's root-cause fix)**
- Symptom: after yanyang.7 the passengers stopped looking like beacons, but **whole trains (and doors, rails, signs, PIDS, lifts) still bloomed warm at night and inside stations**
- Root cause (verified against Beryl 0.2.0-alpha's `entity.vsh`/`entity.fsh` and the vanilla 1.21.1 `LightTexture.pack` bytecode): every MTR static model (`NewOptimizedModel`) **bakes its UV2 lightmap coordinate into a static GPU vertex buffer once at build time**, and that bake has always been the vanilla-GL full-bright constant `pack(15, 15)`. Beryl's entity shader turns that **frozen block component** straight into a **squared warm emission** — `texColor × (240/256)² × 2 × (1 − lightY) × warm ≈ 1.55×**, brightest exactly where `lightY` collapses: nights and roofed stations. **The whole-train warm bloom came from that one baked line**
- Fix: while Beryl owns the GL models the bake becomes `pack(0, 15)` — **block 0 kills the emission term**; **sky 15 keeps Beryl's own ambient light, GGX direct lighting, shadow-map occlusion and fog fully working** (zeroing the sky too would close Beryl's direct-light gate and leave unshaded silhouettes). Plain GL, plain VulkanMod and the native pipeline keep the full-bright bake — **the long-standing MTR cartoon look is unchanged on those paths**
- Also: while Beryl drives the frame, the per-draw static-model brightness (ColorModulator) is pinned at 1 — the old formula hits exactly 0 in fully dark scenes, multiplying away everything Beryl's own lighting just computed (pitch-black silhouettes). The shader pack owns the light; the colour modulator stays neutral. (The GL / VulkanMod paths keep the old formula untouched.)
- The humanoid block-light cap tightens from 8 to **4**: 8 still left a ~0.5× warm bloom on real scenes; 4 leaves ~0.12× — a faint "standing under a lamp" warmth

**Particles glowing — confirmed Beryl's own behaviour, not fixable from MTR**
- You observed particles glowing too — correct: this round confirmed Beryl's `particle.vsh` carries the **identical squared-emission formula**, while vanilla fills particle lightmaps with real block light. Particles near any light source of 10+ bloom warm — that is the shader pack's own design, outside MTR's reach

**AI passenger size / phone pose — re-verified against the vanilla bytecode, no defect found**
- The passenger transform chain was re-derived against the **freshly decompiled vanilla 1.21.1 `LivingEntityRenderer.render` bytecode**: the MTR chain (age scale outermost → flip → 0.9375 → −1.501 → YP180) is **mathematically identical** to vanilla's (`getAgeScale()` scale → YP(180−yaw) → flip → scale → −1.501) — the `0.9375` is exactly vanilla's `Player.getAgeScale()`, every element of the chain commutes, and children keep their feet on the ground exactly like vanilla babies. The phone pivot `(0, 8.2, −7.6)` is within 0.05 blocks of the computed hand-convergence point of the −50° arm pose
- **Request**: if size / phone-pose issues still reproduce **with shaders off** (the native Vulkan pipeline), that is a different rendering path — send that log and it will be fixed there

**Verification**
- Both CI pipelines running on commit cb2874c: Build 1.21.x (1.21.1 / 1.21.4 × Fabric / NeoForge) + Native Vulkan OBJ pipeline (SPIR-V, 6-platform native libs, assemble, build-mod with the 8-platform natives bundled)
- Every conclusion is grounded in the decompiled Beryl 0.2.0-alpha `entity.vsh`/`entity.fsh`/`particle.vsh`, VulkanMod 0.6.7 `VBO.drawWithShader` and vanilla 1.21.1 `LightTexture.pack` / `LivingEntityRenderer` bytecode — not log-line inference

Loader / versions: **1.21.1 & 1.21.4 × Fabric / NeoForge**. The native Vulkan pipeline only enables itself when VulkanMod is detected (except in sessions where Beryl's shaders are driving the frame); everything else stays on the original OpenGL path.
