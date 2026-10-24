中文说明 / English notes

**MTR-Yanyang 4.1.0-beta-2.yanyang.4**（新版本方案：`上游版本-yanyang.N`，可直接替换旧 jar 升级）本次更新：

**AI 乘客**
- 乘客不再全是同一副面孔：约 85% 为成年人（带 ±3% 身高抖动），约 15% 为小孩（小个子 + 相对大头的经典比例），成年人占主导
- 看手机的乘客手中现在真的拿着一部深色手机，随姿态渐入浮现
- 站台/街道乘客的地面高度扫描改为按世界列缓存 500 ms（全部乘客共享），彻底移除了繁忙车站每帧数千次的方块查询

**BVE 音效**
- 修复了自上游 4.0 起就存在的调度音效 N 倍重复播放 bug：同一 tick 内每个音效会被播放 N 次（N=当帧调度数），叠加音源造成相位失真并耗尽混音通道——这正是"BVE 音效播放异常、音轨缺失"的根因；现在每个音效只播放一次
- 首次实现 flange（弯道啸叫）音轨：此前该轨被永久静音且只接入 sound.cfg 的第一条 [Flange] 条目；现在音量随车厢偏航角速度 × 速度驱动，条目跟随轨道运行音索引，且所有 [Flange] 条目全部接入
- 运行音/啸叫音的回退索引改为确定性（取最小键），不再依赖哈希表遍历顺序

**Vulkan / Beryl**（自 beta.3 后的开发版本已随本 tag 一并发布）
- 协议 v2：Vulkan 路径下全部模型渲染数学生效于原生端，每次绘制零 Java 侧计算；Beryl 关闭光影时原生管线始终接管
- UniversalCraft GUI 状态查询通过条件 Mixin 兼容 VulkanMod 0.6.x（修复 GUI 打开即崩溃）；Beryl 开启时模型保持正确世界位置，且可随 Beryl 开关即时重建

**文档**
- 全新 README、精简 CHANGELOG，以及 `docs/PERFORMANCE.md` §6 各模块预测执行效率表

加载器 / 版本：**1.21.1 与 1.21.4 × Fabric / NeoForge**。原生 Vulkan 管线仅在检测到 VulkanMod 时自动启用，其余情况走原 OpenGL 路径。

---

**MTR-Yanyang 4.1.0-beta-2.yanyang.4** (new scheme: `upstream-yanyang.N`; drop-in upgrade over previous jars):

**AI passengers**
- Passengers are no longer identical clones: ~85% adults (±3% height jitter), ~15% children (small body, near-adult-sized head) — adults dominant
- Phone watchers now actually hold a dark phone that materialises as the pose blends in
- Ground-height scans for platform/street passengers are cached per world column for 500 ms (shared across all passengers), removing thousands of per-frame block lookups at busy stations

**BVE sound**
- Fixed the scheduled-sound N× duplication bug (upstream since 4.0): a tick with N scheduled sounds played every sound N times — stacked voices caused phasing distortion and starved the sound engine, the actual root cause of the "abnormal BVE playback / missing tracks" report. Each scheduled sound now plays exactly once
- The flange (curve-squeal) track is implemented for the first time: it was permanently muted and only the first sound.cfg [Flange] entry was wired. Gain now follows the car's yaw rate × speed, the entry follows the rail run sound index, and all [Flange] entries get looping instances
- Run/flange fallback indices are deterministic (lowest defined key) instead of hash-map iteration order

**Vulkan / Beryl** (shipped in dev builds since beta.3, first tagged here)
- Protocol v2: all model rendering math runs natively on the Vulkan path — zero per-draw Java work; the native pipeline takes over whenever Beryl's shaders are off
- UniversalCraft GUI state queries are VulkanMod-0.6.x-safe via conditional mixins (fixes the GUI crash); models keep their correct world positions under Beryl, and rebuild live when Beryl is toggled

**Docs**
- New README, concise CHANGELOG, and a per-module predicted-efficiency table in `docs/PERFORMANCE.md` §6

Loaders / versions: **1.21.1 and 1.21.4 × Fabric / NeoForge**. The native Vulkan pipeline activates automatically only when VulkanMod is detected; everything else uses the original OpenGL path.
