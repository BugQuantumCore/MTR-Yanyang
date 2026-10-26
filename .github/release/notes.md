中文说明 / English notes

**MTR-Yanyang 4.1.0-beta-2.yanyang.5** 本次更新：

**AI 乘客皮肤（本次主打）**
- 皮肤来源彻底更换：内置 **102 个经逐一在线验证的正版（Mojang 优先购买）账号**皮肤池（名字 + 不可变 UUID 均经 api.mojang.com 与 sessionserver.mojang.com 实时核验，皮肤尺寸校验、slim/wide 模型记录）——每个 AI 乘客确定性地"分到"一个正版账号，穿上该账号的真实皮肤
- 在线正版皮肤**默认开启**；首次启动自动生成带说明的配置文件 `config/mtr_passenger_skins.json`，可关闭、可改用 CustomSkinLoader 皮肤站（littleskin 等）、可指定名单、可调缓存时长
- **按 UUID 优先抓取**（改名免疫，每个账号只需一次请求），4 线程共享单例 HttpClient 抓取，并对 Mojang API 限速做了礼貌间隔
- **固定槽位池**：每个乘客的槽位分配稳定，槽位先落在原版默认皮肤上，抓取完成后原位升级为该账号皮肤——乘客不会"变脸"，渲染线程读取无锁
- 磁盘缓存（默认 12 小时 TTL）+ **离线兜底**：断网时沿用上次成功抓取的缓存皮肤（旧的也比没有强）；reload 时代数计数器终止过期抓取，失败账号按 10 分钟退避
- **旧版 64x32 皮肤按原版精确配方转换**：与 1.21.1 `HttpTexture.processLegacySkin` 与 1.21.4 `SkinTextureDownloader.processLegacySkin` 字节码逐指令核对一致（12 次镜像 copyRect、下半区透明填充、头部/身体/腿部区域强制不透明、Notch 帽子透明化 hack），像素访问器用 stonecutter 条件编译适配两版本（getPixelRGBA / getPixelABGR）——Notch、jeb_ 等经典老账号皮肤从此正确渲染

**原生 Vulkan 管线（严重修复）**
- 修复 Java→原生消息线格式：`publish()` 之前把整条 `[u32 类型][消息体]` 数组当作负载发布，而包头已携带类型——**每条**原生消息解析整体右移 4 字节（requestId 错乱 → 每个模型 60 秒超时；INIT_VULKAN 收到错误的 Vulkan 句柄可能原生崩溃）。原生管线此前从 Java 侧根本无法端到端工作；现负载与 Rust 读取端逐字节一致
- 修复延迟拆卸死代码：`disable()`/`shutdown()` 在 `tickTeardown()` 读取前就把实例置空，拆卸路径永远不可达——每次禁用/重连都泄漏原生轮询线程、映射、原生模型与 GPU 纹理池。新增专用拆卸槽位；`shutdown()` 重置引导闩锁，断线重连可正常重新引导
- `initialise()` 在共享内存区创建后、实例发布前失败时不再泄漏映射区与轮询线程；MTL 加载器按 UTF-8 字节精确预留（非 ASCII 路径不再触发回退）；每帧的属性/反射探测提升为类初始化常量；删除死代码 VulkanFrameState

**渲染与内存**
- WorkerThread 剔除循环不再每 ~101 帧多生一个永不退出的循环（此前每小时 ~2100 个，各自每 10ms 苏醒争锁）
- 资源重载时通过存活实例注册表关闭 NewOptimizedModel 的全部 GL 顶点缓冲（此前每次 F3+T / 材质包切换 / Beryl 开关**整套旧模型集的 VAO+VBO 全部泄漏**）；每帧着色器颜色保存/改用线程内暂存数组
- 修复看手机姿态的手臂 yaw 泄漏（任何一个乘客看过手机后，之后渲染的乘客手臂全部内拧）；眼糖（eye candy）OBJ 模型在夜晚/隧道中正确变暗；信号节点扫描缓存 500 ms（此前每个信号每侧每帧 891 次方块扫描）；开门扫描按渲染距离门控；PIDS 分行不再每帧重编译正则；提升多处每帧 ResourceLocation 分配；地面高度缓存键无损位打包；重载时清空乘客动画/渲染缓存

**音效与客户端**
- 车辆循环音按 48 格距离门控：远处车厢不再永久占用 OpenAL ~32 声道池、饿死玩家真正听得见的车辆（繁忙车站"音轨缺失"根因之一）
- BVE 电机音不再让畸形材质包直接崩渲染线程（空声道列表上的 `Math.clamp`）；资源包创建器"speed"试听不再每点一次泄漏整套循环音源；字体加载不再因缺失/损坏字体死循环（有界重试 + 逻辑字体兜底，单工作线程不再卡死）；可重载纹理持有器释放被替换的 GL 纹理；ServerRailPaths.stop() 不再中断服务器停止链
- 模拟核心的 slf4j 日志在**两层构建**中均可用（TSC shadowJar 保留 log4j-slf4j2-impl provider；模组再打包不再最小化移除）——此前模拟核心日志被静默吞掉

**模拟核心（Transport-Simulation-Core，经 CI 补丁随包生效）**
- `VehicleExtraData.copy` 不再在 JSON 往返中序列化整条路径（此前每 tick 每客户端每脏车全路径序列化+反解析后又丢弃大半——服务器上最大的 CPU 热点）
- 模拟追赶循环加上 30 秒墙钟上限 + 中断检查：慢世界不再把 `Main.stop()` 永久挂死（世界卸载"未响应"根因）
- 图刷新时清除陈旧 independentConnections（地图编辑后不再出现错误步行方向/引用泄漏）

**验证**
- 新皮肤管理器两个 stonecutter 变体均通过 1.21.1/1.21.4 精确签名 stub 的 javac 类型检查；旧版转换配方与两版客户端字节码交叉核对
- 修复前对全部模块做过完整审计（nativeobj、render/model、sound/client/mixin/data、TSC），每项修复逐条与审计结论复核

加载器 / 版本：**1.21.1 与 1.21.4 × Fabric / NeoForge**。原生 Vulkan 管线仅在检测到 VulkanMod 时自动启用，其余情况走原 OpenGL 路径。

---

**MTR-Yanyang 4.1.0-beta-2.yanyang.5**:

**AI passenger skins (headline)**
- The skin source is replaced wholesale: a bundled pool of **102 genuine Mojang premium accounts**, each verified live against api.mojang.com + sessionserver.mojang.com (name + immutable UUID pair, skin dimensions checked, slim/wide model recorded). Every AI passenger deterministically gets one account and wears its real premium skin
- Online premium skins are **ON by default**; `config/mtr_passenger_skins.json` is auto-created with documented defaults and can disable them, switch to a CustomSkinLoader site, supply an explicit name list, or tune the cache TTL
- **UUID-first fetch** (rename-proof, one profile request per account), a 4-worker pool sharing a single HttpClient, politely spaced for the Mojang rate limits
- **Fixed-size slot pool**: each passenger's slot assignment is stable, slots start on vanilla defaults and upgrade in place to their account's live skin — no skin hopping, lock-free reads on the render thread
- Disk cache (12 h TTL by default) with **stale-cache offline fallback** (stale beats nothing); a generation counter aborts stale fetchers across reloads and failed accounts back off for 10 minutes
- **Legacy 64x32 skins are converted with the exact vanilla recipe**, verified instruction-for-instruction against the 1.21.1 `HttpTexture.processLegacySkin` and 1.21.4 `SkinTextureDownloader.processLegacySkin` bytecode (the twelve mirrored copyRect calls, the transparent bottom-half fill, the opaque-region fixups and the Notch hat hack), with pixel accessors behind stonecutter conditionals (getPixelRGBA vs getPixel) — classic accounts like Notch and jeb_ render correctly at last

**Native Vulkan pipeline (critical)**
- Fixed the Java→native message wire format: `publish()` was passing the whole `[u32 type][body]` array as the payload while the header already carries the type — **every** native message parse was shifted by 4 bytes (garbage requestIds → 60 s timeouts per model; garbage Vulkan handles in INIT_VULKAN, a native-crash hazard). The native path could never have worked end-to-end from Java; the payload now matches the Rust reader byte-for-byte
- Fixed the deferred-teardown dead code: `disable()`/`shutdown()` nulled the instance before `tickTeardown()` could read it, so teardown never ran — every disable/reconnect leaked the native poller thread, mappings, models and GPU texture pools. A dedicated teardown slot fixes the ordering; `shutdown()` re-arms the boot latch so reconnects re-boot cleanly
- `initialise()` no longer leaks the shared-memory region + poller when it fails between region creation and publication; the MTL loader sizes UTF-8 byte-exactly; per-frame property/reflection probes hoisted to class init; dead VulkanFrameState removed

**Rendering & memory**
- The WorkerThread culling loop no longer spawns an extra never-exiting loop every ~101 frames (~2100/hour, each waking every 10 ms and contending the texture-queue lock)
- NewOptimizedModel GL vertex buffers are closed on resource reload via a live-instance registry (previously **the entire previous model set's VAO+VBO ids leaked per F3+T / pack change / Beryl toggle**); the per-draw shader-colour save/restore reuses a render-thread scratch
- Phone-pose arm yaw no longer leaks onto every subsequent passenger (twisted arms after any phone watcher); eye-candy OBJ models dim correctly at night / in tunnels; signal node scans are cached for 500 ms (were 891 block scans per signal per side per frame); door-open scans are gated by render distance; PIDS row splitting no longer recompiles a regex per frame; per-frame ResourceLocation allocations hoisted; ground-height cache keys bit-packed; passenger animation/render caches clear on reload

**Sound & client**
- Vehicle looping sounds are distance-gated at 48 blocks: far cars no longer permanently occupy OpenAL's ~32-channel pool and starve the vehicles the player can actually hear
- BVE motor sounds no longer crash the render loop on malformed packs (`Math.clamp` on empty channel lists); the Resource Pack Creator's "speed" test no longer leaks a full looping voice-set per click; font loading no longer spins forever on a missing/corrupt font (bounded retries + logical-font fallback — the single worker thread stays alive); reloadable-texture holders release replaced GL textures; `ServerRailPaths.stop()` can no longer abort the server-stop chain
- slf4j logging for the shaded simulation core works at **both** build layers (the TSC shadowJar keeps its log4j-slf4j2-impl provider; the mod's re-shadow no longer minimizes it away) — simulation-core logs were silently swallowed before

**Simulation core (Transport-Simulation-Core, shipped via the CI patch)**
- `VehicleExtraData.copy` no longer serializes the entire path in the JSON round-trip (was the #1 CPU hot spot: per dirty vehicle per client per tick, then discarded all but a slice)
- The simulator catch-up loop is bounded (30 s wall + interrupt check) — a slow world can no longer wedge `Main.stop()` forever (the world-unload "not responding" hang)
- Stale `independentConnections` no longer survive graph refreshes (wrong walking directions / reference leaks after map edits)

**Verification**
- Both stonecutter variants of the new skin manager were javac-type-checked against accurate 1.21.1/1.21.4 signatures; the legacy conversion is cross-verified against both clients' bytecode
- The full module set was audited before fixing (nativeobj, render/model, sound/client/mixin/data, TSC); every fix was re-checked line-by-line against the audit findings

Loaders / versions: **1.21.1 and 1.21.4 × Fabric / NeoForge**. The native Vulkan pipeline activates automatically only when VulkanMod is detected; everything else uses the original OpenGL path.
