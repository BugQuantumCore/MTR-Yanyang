# Changelog

Concise, newest first. Tag scheme changed at `4.1.0-beta-2.yanyang.4` (previously `YYMTR-4.1.0-beta.N`); the jars are drop-in upgrades of one another.

## 4.1.0-beta-2.yanyang.20 (2026-11-27)

- 修复 yanyang.19 引入的 OBJ 轨道模型碎裂（含默认 3D 轨道，OpenGL 与 VulkanMod/Beryl 均受影响）：合批回放把三角形流写入四边形缓冲导致几何错乱。OBJ 网格现以退化四边形回放，并运行时探测目标图层模式，模式不匹配时自动回退逐实例绘制，绘制正确性与合批性能同时保留。

## 4.1.0-beta-2.yanyang.19 (2026-11-21)

- 资源包缺失的风挡/护栏贴图不再花屏或令 VulkanMod 崩溃渲染线程（丢弃引用，仅记录一次日志）。
- 单个异常绘制按（贴图, 图层）跳过并记录，不再中断整帧渲染。
- 一帧内重复 ≥3 次的小网格（轨道段、共享车厢部件、逐车厢脚本模块）合并为单次绘制调用。

## 4.1.0-beta-2.yanyang.18 (2026-11-14)

- 渲染、Vulkan 原生管线（协议 v3）、乘客模拟与 NeoForge 打包的批量修复；详见 `docs/AUDIT_2026-11-14.zh-CN.md`。

## 4.1.0-beta-2.yanyang.17 (2026-11-03)

- 全部 Radiance 版本（0.1.0–0.1.6）可安全同载：0.1.6 之前版本打开 MTR 界面不再崩溃 JVM，改为双语升级提示。

## 4.1.0-beta-2.yanyang.16 (2026-11-02)

- 落到静止列车车顶可正常站立（按入箱方向解算）；NeoForge 版可在 VulkanMod 移植版下运行（含纹理路由）；mods.toml 依赖声明修复；Radiance 检测（原生管线正确关闭）。

## 4.1.0-beta-2.yanyang.15 (2026-11-01)

- 碰撞箱兼容 Lithium（`Entity#move` 尾部兜底解穿插）；手机观看姿势重做；日志与缓存边界修复。

## 4.1.0-beta-2.yanyang.14 (2026-11-01)

- AI 乘客半尺寸根因修复（`EntityModel.young` 构造默认值）；列车完整碰撞箱，行进中可撞飞前方玩家。

## 4.1.0-beta-2.yanyang.13 (2026-11-01)

- AI 乘客碰撞箱（`EntityGetter#getEntityCollisions`）；行走动画贴合地面并平滑；启动崩溃热修（接口 mixin 声明）。

## 4.1.0-beta-2.yanyang.12 (2026-10-31)

- 乘客模型年龄变化与手机姿势；站台/车厢乘客渲染与动画缓存优化。

## 4.1.0-beta-2.yanyang.11 (2026-10-31)

- VulkanMod 下灯带/灯组经信标管线渲染错乱修复（改走 NEW_ENTITY 图层）；渲染与模拟多项修复。

## 4.1.0-beta-2.yanyang.10 (2026-10-30)

- 渲染管线与乘客模拟批量修复；详见当期发布说明。

## 4.1.0-beta-2.yanyang.9 (2026-10-30)

- 原生管线与渲染、碰撞、乘客、声音批量修复；详见当期发布说明。

## 4.1.0-beta-2.yanyang.8 (2026-10-29)

- Beryl 下车辆与静态模型的暖色泛光根因修复（烘焙光照 `pack(0,15)`，ColorModulator 中立化）；乘客光照上限降至 4。

## 4.1.0-beta-2.yanyang.7 (2026-10-27)

- Beryl 切换硬崩溃深层修复（60 帧过渡宽限窗口）；Beryl 下乘客泛光根因（实体着色器平方发光公式）。

## 4.1.0-beta-2.yanyang.6 (2026-10-26)

- 会话中途关闭 Beryl 光影的驱动级崩溃修复（原生管线会话内闩锁关闭）；车厢乘客泛光修复（按位置采样光照）。

## 4.1.0-beta-2.yanyang.5 (2026-10-26)

- AI 乘客皮肤改用 102 个正版账户皮肤池（确定性分配、磁盘缓存、离线回退、64x32 旧格式精确转换）；原生管线消息格式与资源泄漏修复；渲染/内存/声音/模拟批量优化。

## 4.1.0-beta-2.yanyang.4 (2026-10-24)

- 乘客年龄变化与手机姿势；BVE 声音修复（计划轨道重复播放、轮缘啸叫）；Vulkan 协议 v2（OBJ/BlockBench 全原生渲染）；UniversalCraft 界面 VulkanMod 安全化；Beryl 下模型位置正确。

## YYMTR-4.1.0-beta.3 (2026-10-23)

- 原生 Vulkan 管线覆盖全部 8 平台并经 CI 校验内嵌；VulkanMod 0.6.7 兼容与安全回退。

## YYMTR-4.1.0-beta.2 (2026-10-22)

- Rust/C++ 原生 Vulkan OBJ 管线首次随发布携带。
