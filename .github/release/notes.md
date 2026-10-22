中文说明 / English notes

**YYMTR 4.1.0-beta.3** 是 4.1.0-beta.2 的 natives 更新版：原生 Vulkan OBJ 渲染管线现已为**全部 8 个平台**构建二进制 —— Windows x64/ARM64、Linux x64/ARM64、macOS ARM64、Android ARM64、iOS ARM64、iPadOS ARM64（iPadOS 与 iOS 共享同一 iPhoneOS 平台二进制，逐字节一致）。

本次更新：

- 每个发布 jar 内含全部 8 平台的 `wrvk` 原生库（Rust 传输核心 + C++ Vulkan 渲染器），CI 对每个 jar 逐一断言 8 平台齐全
- Java 端平台自动检测扩展：Android（`/system/build.prop` / Dalvik 特征）、iOS 与 iPadOS（`/var/mobile` 沙盒根 + `uname -m` 设备型号），Windows / Linux ARM64 原生支持
- iOS 构建额外探测 `@executable_path` Frameworks 内嵌的 MoltenVK；dylib 以 ad-hoc 签名发布，最低部署目标 iOS 12.0
- 修复 build.rs 的 `WRXR_SKIP_CPP_BUNDLE` 缓存污染隐患（声明 `rerun-if-env-changed`）

加载器 / 版本：**1.21.1 与 1.21.4 × Fabric / NeoForge**。native 管线仅在检测到 Vulkan 图形后端时自动启用，任何失败都会回退 Java GL 渲染器，无需手动开关。

---

**YYMTR 4.1.0-beta.3** is a natives refresh of 4.1.0-beta.2: the native Vulkan OBJ pipeline now ships binaries for **all 8 platforms** — Windows x64/ARM64, Linux x64/ARM64, macOS ARM64, Android ARM64, iOS ARM64 and iPadOS ARM64 (iPadOS shares the byte-identical iPhoneOS platform binary).

What's new:

- All 8 `wrvk` native libraries (Rust transport core + C++ Vulkan renderer) are packaged into every release jar; CI asserts all 8 platforms inside each jar before it can ship
- Extended Java-side platform auto-detection: Android (`/system/build.prop` / Dalvik markers), iOS & iPadOS (`/var/mobile` sandbox root + `uname -m` device model), plus native Windows / Linux ARM64 support
- iOS builds additionally probe `@executable_path` Frameworks for an embedded MoltenVK; the dylib is ad-hoc signed with an iOS 12.0 minimum deployment target
- Fixed the `WRXR_SKIP_CPP_BUNDLE` build-script cache-poisoning hazard (declared `rerun-if-env-changed`)

Loaders / versions: **1.21.1 and 1.21.4 × Fabric / NeoForge**. The native pipeline activates automatically only when a Vulkan graphics backend is detected and falls back to the Java GL renderer on any failure — no user toggle involved.
