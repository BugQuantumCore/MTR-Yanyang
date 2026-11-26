# MTR-Yanyang 4.1.0-beta-2.yanyang.19

适用组合：Minecraft 1.21.1 / 1.21.4，Fabric / NeoForge。

此版本在 yanyang.18 的基础上发布三项渲染修复：资源包缺失的风挡/护栏贴图处理、逐绘制崩溃防护、多实例合批渲染。构建与测试全部在 GitHub Actions 执行，没有在本地构建、测试、运行游戏或进行性能基准。发布附件的具体来源、构建 run、提交 SHA 和 SHA-256 以 release 随附的构建来源清单及校验和为准。

## 主要修改

- **风挡贴图缺失修复（2026-11-21 崩溃）**：资源包未提供的风挡/护栏面板贴图不再作为"缺失贴图"引用进入渲染器。原版 GL 下它会在每节车厢之间渲染出 missingno 棋盘格（`Failed to load texture: mtr:wr2a03/wr2a03_connector_*.png`）；在 VulkanMod 下，其缺失贴图回退携带 null `VkGlTexture`，会直接从 `RenderLayer#startDrawing` 崩溃渲染线程。现在 `ModelProperties` 在贴图文件不存在时丢弃该引用——面板按可选字段未设置的样式渲染；动态贴图（`dynamic/...` id）豁免，每次丢弃仅记录一次日志。
- **逐绘制崩溃防护**：`MainRenderer` 包装每个计划绘制；异常的绘制（贴图绑定损坏、动态贴图已释放、驱动错误）按 (identifier, layer) 跳过并各记录一次日志，不再连带杀死渲染线程与游戏，帧内其余部分正常渲染。
- **多实例合批渲染**：一帧内排队多次的网格（≥ 3 个实例、≤ 4096 顶点——重复的轨道段、每节车厢共享的部件、逐车厢脚本模块）改为把所有实例回放到对应 (layer, texture) 桶的同一个共享计划缓冲中一次绘制，替代原先每实例一次 `setupRenderState` + `drawWithShader`。300 米自定义轨道模型侧线原先意味着每帧数百次额外绘制调用，在 Iris + 光影包下足以显著拖垮帧率。GL 路径保留顶点发射器（组的存储数据本就比模型存活更久，仅闭包被固定），light stage → queued layer 映射保持 VulkanMod 信标光柱重定向（`lightStagesNeedNewEntity`）不变。

TSC 依赖保持固定提交 `601876836d8eb4fdf010c6a2ff969dda8ee19b72`。

## 发布检查

发行流程要求来源是 `1.21.x` 上成功的完整原生 push 构建，并核对其源码 SHA。四个发行 JAR 必须包含与该运行原生产物逐字节一致的八份平台库及六个 shader；release tag 指向该构建的源码提交，而不是后续发布配置提交。

## 使用与验证边界

- 根据 Minecraft 版本和加载器选择对应的 JAR，替换旧版模组文件，避免同时保留多个 MTR-Yanyang 版本。
- 未进行真实 GPU、Radiance/Beryl 游戏画面、帧时间或显存基准，不能据此承诺具体 FPS 提升或所有驱动/模组组合兼容。
- 合批收益取决于场景中重复网格的数量；逐像素验证与实机帧率对比不在本次 CI 范围内。
