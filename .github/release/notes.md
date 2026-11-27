# MTR-Yanyang 4.1.0-beta-2.yanyang.20

适用组合：Minecraft 1.21.1 / 1.21.4，Fabric / NeoForge。

## 修复

- **轨道模型渲染错乱（yanyang.19 回归）**：多实例合批把 OBJ 轨道模型（含默认 3D 轨道）的三角形顶点流写入四边形模式的共享缓冲，几何被按每 4 顶点重组，首个三角形之后全部错位、拉丝。OpenGL 与 VulkanMod（含 Beryl 光影）均受影响——两者都走 Java 渲染路径。修复：OBJ 网格按退化四边形（v0,v1,v2,v2，零面积三角形被光栅化器丢弃）回放，几何精确；同时运行时探测目标图层模式，模式不匹配时自动回退逐实例绘制，未来版本变更层模式也安全。合批带来的 draw call 削减保留。

构建与测试全部在 GitHub Actions 执行；未做本地实机验证。发布附件来源、构建 run、提交 SHA 与 SHA-256 见随附 `build-provenance.json` 与 `SHA256SUMS`。
