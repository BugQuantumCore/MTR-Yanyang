# MTR-Yanyang 4.1.0-beta-2.yanyang.18

适用组合：Minecraft 1.21.1 / 1.21.4，Fabric / NeoForge。

此版本汇总本轮源码审查、性能优化和回归修复。构建与测试全部在 GitHub Actions 执行，没有在本地构建、测试、运行游戏或进行性能基准。发布附件的具体来源、构建 run、提交 SHA 和 SHA-256 以 release 随附的构建来源清单及校验和为准。

## 主要修改

- 修正 Rust/C++ Vulkan 设备句柄、std140/UBO 对齐、内存类型选择、初始化和销毁路径，以及 OBJ 材质/组索引。
- 每次模型变换仅放置一次整模型，再选择材质网格；复用帧数据、相同姿态与绑定，减少重复计算和分配。
- 原生通信升级到协议 v3；在渲染线程同步录制宿主命令缓冲，复用宿主纹理并维护渲染状态。
- Radiance 使用可捕获的批量绘制通道；在捕获边界正确转换原始亮度为打包光照值，保留已经打包的光照。修复 OpenGL VBO 绑定和颜色恢复。
- 修复列车/乘客碰撞快照、完整车辆键、高帧率速度估算和包含关系推出；优化旋转 AABB 计算及 Blockbench 局部边界。
- 修复 AI 乘客首节车厢选择、连接段、非法编组、已删除车辆引用和同线路行程同步；补齐步行换乘网格范围及路径拒绝回退。
- 限制动态纹理队列和每帧上传量，后台裁切、隔离过期结果；释放地图 GPU 缓冲，修复缓存、路径和输入边界。
- 颜色选择器改用固定数量的四边形，由 GPU 插值/混合替代逐像素 CPU 几何生成。
- 修复信号缓存键、空 PIDS 语言、方块 tick 调度、传感器存档、播报队列、标牌输入及多处 UI 状态问题。
- 修复 NeoForge 合并依赖时的重复注解类及 Maven 元数据，保留 TSC 已重定位的注解和一份元数据；不改变服务文件或许可证处理，并让最终重复条目直接阻断构建。

TSC 依赖固定为已合并提交 `601876836d8eb4fdf010c6a2ff969dda8ee19b72`。主模组与依赖修改分别通过 PR #2、PR #1 追踪。

## 发布检查

发行流程要求来源是 `1.21.x` 上成功的完整原生 push 构建，并核对其源码 SHA。四个发行 JAR 必须包含与该运行原生产物逐字节一致的八份平台库及六个 shader；release tag 指向该构建的源码提交，而不是后续发布配置提交。

原生平台目录包括 Windows x64/ARM64、Linux x64/ARM64、macOS ARM64、Android ARM64、iOS ARM64 和 iPadOS ARM64。iPadOS 使用 iOS 库的副本，不是独立设备测试。普通 Java 构建产物不能替代已注入原生库的发行 JAR。

## 使用与验证边界

- 根据 Minecraft 版本和加载器选择对应的 JAR，替换旧版模组文件，避免同时保留多个 MTR-Yanyang 版本。
- 未进行真实 GPU、Radiance/Beryl 游戏画面、帧时间或显存基准，不能据此承诺具体 FPS 提升或所有驱动/模组组合兼容。
- Android/iOS 为交叉编译；录制式 Vulkan 单元测试不是显卡驱动验证。Java/C++ 模型 golden 等价比较尚未执行。
- 新增碰撞仅作用于客户端玩家；保守 AABB 不等于服务端权威碰撞、精确 OBB、连续碰撞检测或斜坡地板。
- GPU fence/frame-slot 所有权、原生失败后的完整模型回退、每顶点颜色、共享模型跨列车文字/门映射、实际嵌套 Blockbench 父变换链，以及部分资源生命周期和服务器授权仍有后续工作。

详细覆盖、修复和未完成项见 `docs/AUDIT_2026-11-14.zh-CN.md` 及专项审查记录。本说明没有将源码审查或 CI 成功等同于游戏实机验证。

## 本次发布的执行证据

- 源码提交：`9c2ccf62d8d0b24aecc01b12153d7408dba88f6a`。
- [完整原生构建、测试和四个发行 JAR 校验](https://github.com/BugQuantumCore/MTR-Yanyang/actions/runs/43532827781)：成功。
- [Fabric/NeoForge × 1.21.1/1.21.4 构建与测试](https://github.com/BugQuantumCore/MTR-Yanyang/actions/runs/43532827744)：成功。
- [发布来源校验器回归](https://github.com/BugQuantumCore/MTR-Yanyang/actions/runs/43532827727)：成功。
- 每个发行 JAR 已包含对应原生库；普通安装无需单独解压原生库 ZIP。
- `SHA256SUMS` 列出发行附件校验和；`build-provenance.json` 记录构建、源码、TSC、artifact 与内嵌库/shader 摘要。
