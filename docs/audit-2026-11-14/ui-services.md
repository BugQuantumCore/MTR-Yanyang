# 界面、地图、字体、配置与资源包服务专项审查

审查分支：`perf/1.21-render-simulation-audit`。工作区：`MTR-Yanyang`。

## 范围和验证边界

已逐文件阅读 `src/main/java/org/mtr/{screen,widget,servlet,font,map,legacy,config}` 当前 **103 个 Java 文件**，包括包声明及本轮新增的 3 个辅助类。也为生命周期修复阅读了 `cache/CachedFileProvider.java`、`cache/CachedFileResource.java`，并核对 `Drawing`、复用的 Elementa/UniversalCraft 绘制接口、相关调用处和新增测试源文件。下方提供完整逐文件清单。

用户明确禁止本地构建与测试，本专项未运行 Gradle、javac、CMake、Cargo、单元测试、游戏或 GPU 基准。只做源代码阅读、编辑、差异检查及 `git diff --check`。测试仅新增源码，最终构建与运行结果由主审查任务在 GitHub Actions 汇总。性能收益是源码工作量推导，不是 FPS/帧时间实测。

## 已落实且冻结的修复

1. **地图 GPU 缓存生命周期（P1）**：原 `MapTileResource.dataUpdated()` 替换 VertexBuffer 时未关闭旧对象；提供器过期驱逐也仅丢弃引用。现在 `CachedFileResource/Provider` 实现 `AutoCloseable`，过期及整体关闭会释放资源；地图缓存更新、删除、空图均关闭旧 buffer。新 buffer 构建失败前保留原 buffer。提供器关闭后不再创建资源。新增 `CachedFileProviderTest` 覆盖驱逐、存活项、重复 close、关闭后读取。主审查者负责 `MTRClient` 更换世界/断线时调用旧提供器 close，及 `MapTileProvider/MapCachePath` 路径验证集成。
2. **资源包导出路径与 ZIP 资源路径（P1）**：导出 `name` 原来进入文件路径；资源标识符直接拼成 ZIP entry。新增纯 Java `ResourcePackFileHelper`，验证目录段、namespace、资源路径与导出名称，拒绝绝对路径、斜杠/反斜杠、空段、冒号、控制字符、末尾点/空格；要求导出结果父目录等于资源包根目录。包括 `mtr:.. /outside` 的 Windows 路径边界回归案例。主审查者另修复不可信服务器 world ID 的地图缓存路径问题；该项不是本辅助类完成的。
3. **上传/ZIP 解压预算（P1）**：`ResourcePackCreatorUploadServlet` 限制单项 64 MiB、合计 256 MiB、4096 entries，按 8 KiB 流式读取并在超限后拒绝。ZIP 解析失败不会执行该 ZIP 的暂存任务；单独文件上传仍可能在后项拒绝前应用前项，没有请求级事务。PNG 不再无谓解码为 UTF-8 字符串。`ResourcePackFileHelperTest` 覆盖有效 Unicode 名称、路径拒绝及读取边界。主审查者负责 Webserver 的 multipart 请求限制配置。限制按一次请求生效，不限制整个编辑会话累计资源量，亦不等价于图片解码像素预算。
4. **色板 CPU 几何（P2，GPU 插值优化）**：`ColorInputComponent` 原先按每 0.5 GUI 像素生成一个四边形并创建 Color；legacy `ColorSelectorWidget` 按每像素嵌套循环。两者改为 `ColorPickerGeometry`：HSB 平面固定 **2 个四边形**，hue 条固定 **6 个分段四边形**。两层一维插值分别表达白色→纯 hue 及黑色 source-alpha 覆盖，避免直接对一个双变量颜色面做三角形插值的对角接缝。预览及选中标记仍单独绘制。legacy 鼠标坐标直接计算，保持其原有轴向和边界约束，显式防止 1 像素范围除零。
   - Elementa 复用 `ImageComponentBase` 已有 `COLOR_QUAD_PIPELINE_ALPHA`，legacy 继续使用 `RenderType.gui()`；不新增 shader/渲染后端。
   - **颜色验证边界**：`RGB = v × ((1-s) + s × HueRGB)` 仅是通道代数；sRGB 目标在线性空间混合、不同 Vulkan blend 实现、像素中心采样、量化和终端 framebuffer alpha 都可能导致与旧 CPU 逐像素路径不同。新 `ColorPickerGeometryTest` 覆盖固定几何数量、角点、两种轴向、理想 source-alpha 数学关系（1 通道值舍入容差），不证明实际 GPU 像素等价。必须在 OpenGL/Vulkan 与所支持 UI 管线上检查颜色、alpha、选择标记及无对角接缝。未声称像素完全等价或 GPU/FPS 实测通过。
5. **红石选项双重翻转（P2）**：`CheckboxComponent` 已在内部翻转状态；`SignalColorScreen` 回调再次翻转会取消点击。删除两处重复翻转，保留关联选项逻辑。
6. **电梯双面选项初值（P2）**：`LiftCustomizationScreen` 缺失 `setChecked(lift.getIsDoubleSided())`。现在显示现有状态，后续点击不会从错误的未选中状态开始。
7. **Wikipedia 预览缓存并发（P2）**：`VehicleSelectorScreen` 原 fastutil AVL map 同时被 UI 与异步线程读写；异步启动后写空占位还能覆盖先完成的结果。改用 `ConcurrentHashMap` 与先行 `putIfAbsent`。保留原失败后不重试行为，未扩大成网络请求重构。

## 尚未修改的确认问题与后续建议

### P1：需要有针对性的后续修复

- **预览 GPU target 生命周期和异常恢复**：`widget/PreviewBoxComponent.java:125` 仅在尺寸变化时 destroyBuffers；类无 close/dispose，构造者 `EyeCandyScreen`、`LiftCustomizationScreen`、`RailModifierScreen` 也未提供关闭释放路径。重复打开有预览的界面会遗留原生 GPU target。`drawFrameBuffer()` 设置 framebuffer/projection/depth 后直接调用 `onDraw.accept`，缺少 finally，异常可能污染后续渲染状态；创建前未拒绝非正尺寸。后续应接入真实屏幕生命周期，并在 finally 恢复状态。需游戏内反复打开关闭/缩放、异常回调和 Vulkan target 状态验证，不能仅靠无 GPU 单元测试确认。
- **资源包 creator 共享可变状态**：`servlet/AbstractResourcePackCreatorServlet.java:32` 的静态 MODELS、TEXTURES、resourceWrapper 由 servlet worker 与客户端线程访问，无一致的串行化/快照协议。上传/clear/export 并发时可能出现并发修改或混合版本导出。后续应让所有 editor state 变更在明确 owner 线程上排队，后台导出只读取稳定快照。新增上传字节限额没有解决此问题。
- **PNG 解码没有像素预算**：同文件 `:72` 对上传字节直接调用 `NativeImage.read`，压缩字节上限仍允许极大宽高图片触发巨量 native 内存申请。后续先读并验证图像维度、像素数与总 decoded-byte 预算，再在正确线程上传 GPU；回归需恶意尺寸 PNG 与 native 资源释放验证。

### P2：可定位的性能及正确性问题

- **滚动列表/标志选择器未按视口裁剪 CPU 工作**：`widget/ListComponent.java:78` 遍历所有匹配行，为每行构造 deferred render、文字布局和图标，即便父 ScrollComponent 最终会 scissor；`SignButtonsComponent.java:64` 同样为所有搜索结果画背景并调用 `SignResource.render`。建议显式可见区计算/列表虚拟化，维持总高度及正确 hover 行。legacy `ScrollableListWidget` 有可见区分支，但 `:46` 每帧调用 `initDimensions()` 全表遍历和可能的文字测量，应在数据、字体/语言、宽度或展开状态变更时失效缓存。
- **过滤列表的索引错位（确认可影响排序）**：`widget/ListItem.java:168–193` 仅在匹配过滤项后递增 indexList，并在遍历子项之前递增父项索引。`screen/ListSelectorScreen` 的选中列表也有搜索（`:113`），排序按钮 `:138` 把过滤后的 indexList 当作原 selectedData 的下标。例：原列表 A,B,C，筛选 C 后按向下/向上会操作 A 对应的原下标。需要区分源索引和显示行索引，并对过滤/嵌套列表增加纯逻辑回归。递归 consumer 返回 true 未传播到父层，legacy 列表的提前终止也不完整。按主审查者冻结要求本轮不扩大修改。
- **地图极小缩放工作量**：`widget/MapComponent.java:301` 以 `scale × 256` 为步长访问 tile；允许 1/128 的比例时每个 tile 仅 2 GUI 像素，循环数量随可见面积增长。加上 `MapTileResource.generate()` 的 256×257 高度查询、逐像素 BlockPos/Color 与同步文件读写，打开/拖动地图会有卡顿风险。缓存生成节流限制创建频率，但不减少所有 tile lookup。应分离世界 owner-thread 采样、后台压缩/写盘，并建立多级瓦片/LOD；不应直接把世界查询放任意 worker/GPU。
- **地图临时状态增长及重复分配**：`MapComponent` 每帧复制 stations/homes/landmarks/depots 为 ObjectArraySet，遍历所有 saved rail 平面索引；tileOpacityValues 仅在再次访问并发现 null 时删除，持续浏览中可积累离屏 key。可在数据 revision 改变时更新不可变视图，并对 opacity 做可见集清理；须保留编辑中临时区域与动画行为。
- **地图缓存格式未校验**：`MapTileResource.dataUpdated():151` 按 5-byte RLE 访问，没有验证整除或解码像素总数；`ImageConverter` 构造同样信任 count 并写入固定像素数组。损坏的本地缓存可能在后续 generate 中抛异常，文件读取本身也无尺寸上限。建议解析独立的有界解码器，坏缓存丢弃/重生成；这是本地缓存稳健性，不宣称已证实远程入口。
- **主线程资源包处理**：`ResourcePackCreatorUploadServlet.java:220` 在 Minecraft.execute 内做模型转换、ZIP 压缩、文件写入；`ReloadCustomResourcesScreen.render():27` 等十余帧后同步 task.run，loading screen 不会让任务异步。需要把纯解析/编码/磁盘操作转到有界 executor、GL/游戏状态修改保留 owner thread，并在 callback/finally 完成响应。
- **HTTP 请求无界/失败完成**：`ClientServlet.java:27` 用 IOUtils.toString 读取任意正文，`:32` 关闭 async timeout；creator servlet 也用 timeout=0。`RequestHelper.onFailure()` 只记日志，不调用 completion callback。建议正文大小/并发数/响应等待时限与成功失败统一完成协议。本仓库没有 Webserver 实现，尚未证明它仅绑定 loopback，不能据此宣称已证实互联网暴露；授权/origin 边界需要审查依赖实现。
- **发车时刻输入可能长循环/溢出**：`DepotScreen.java:350` 把可输入的乘数直接解析并循环，文本长度25仍足以表示巨大计数；例如 `0:0:0+99999999*0:0:0` 会在 add/remove 时反复处理相同时间。`+1`、小时×3600、i×interval 均用 int 可能溢出。应验证非负有界计数、用 long/精确运算，以及对零间隔直接去重；需 GitHub 纯解析器回归，勿在本地跑卡顿输入。
- **重复列表重建/排序**：`DashboardScreen.onTick():279` 与 `LiftSelectionScreen.onTick():70` 每 tick 创建并排序 ListItems；`ListSelectorScreen.selectData():68` 每次初始选择都重建两侧并排序，批量装载趋于二次开销。建议批量更新入口/dirty revision，保留展开状态与筛选。
- **轨道预览与速度输入**：`RailModifierScreen.onDrawPreview():228` 每帧 createRailCopy 并重算预览几何，可缓存输入签名变化后的几何；speedInputComponent 只创建/初始化，未在 createRailCopy 或提交处读取，速度控件看起来不可生效。应先确认 core Rail 速度更新接口，再写正确回归，避免猜测 setter。
- **异步设置覆盖**：`RouteScreen.java:95` 默认 false checkbox 发请求等待公告禁用状态，`:155` 关闭时无条件发送当前值；在响应到达前关闭可能把 true 写成 false。应区分尚未加载/用户修改，加载前禁用或不提交，并检查响应回到 UI owner thread。
- **字体布局重复成本**：`FontRenderHelper` 每次 split 文本、创建 Component/FormattedCharSequence 及多组数组，测量时和最终 renderRaw 时各测量一次 width。复用宽度可先减少重复工作；跨帧缓存必须纳入字体、语言、资源重载、布局选项变化，不能只以原字符串为键。
- **配置健壮性与过时注释**：`config/Client.java:67` 每次 replaceFirst 重新编译 regex，配置 regex 非法可抛异常；`:86` cycleLanguageDisplay 直接访问 nullable 字段而不是其安全 getter。`NativeCommMethod` Javadoc 的旧 poller 描述已由主审查者同步为共享内存构建 + 渲染线程同步 JNI 帧提交。

其他低风险观察：`ScrollablePanelWidget.scrolling` 没有 mouseReleased 清零；部分 EyeCandy/Lift 预览明确标 TODO；这些保留为后续 UI 完整性工作，不当作已完成性能优化。审查没有把第三方依赖的实现、生成代码或运行环境行为等同于本目录的已读源码。

## 建议的 GitHub / 游戏验证顺序

1. Java 多版本 CI 编译及新增 `CachedFileProviderTest`、`ResourcePackFileHelperTest`、`ColorPickerGeometryTest`（另有主审查者 MapCachePath 测试）。
2. 游戏端检查两个色板四角、hue 六段连接、拖动和文字输入、透明度与不同 GUI scale；覆盖 OpenGL/Vulkan/Radiance 所允许的组合。记录实际目标色彩空间后才判断与旧版本的颜色差异。
3. 打开/拖动地图、触发缓存再生成与过期，断线/换世界反复循环，对照 native GPU 对象数量和显存；验证原有地图颜色/坐标未变化。
4. 上传正常 ZIP/PNG/OBJ 与限额边界，拒绝 traversal/超限时确认未导出路径外文件且无部分更新。并发 creator state 和图片解码预算仍应单独修复后再声称受控。

## 完整 Java 文件覆盖清单

以下均已阅读完整内容（含当前新辅助类）；不是仅文件名或搜索命中覆盖。

### config（6 个文件）

- `src/main/java/org/mtr/config/Client.java`
- `src/main/java/org/mtr/config/Config.java`
- `src/main/java/org/mtr/config/LanguageDisplay.java`
- `src/main/java/org/mtr/config/NativeCommMethod.java`
- `src/main/java/org/mtr/config/Server.java`
- `src/main/java/org/mtr/config/package-info.java`

### font（4 个文件）

- `src/main/java/org/mtr/font/FontRenderHelper.java`
- `src/main/java/org/mtr/font/FontRenderOptions.java`
- `src/main/java/org/mtr/font/FontResourceBase.java`
- `src/main/java/org/mtr/font/package-info.java`

### legacy（6 个文件）

- `src/main/java/org/mtr/legacy/package-info.java`
- `src/main/java/org/mtr/legacy/resource/CustomResourcesConverter.java`
- `src/main/java/org/mtr/legacy/resource/LegacyObjectResource.java`
- `src/main/java/org/mtr/legacy/resource/LegacyRailResource.java`
- `src/main/java/org/mtr/legacy/resource/LegacySignResource.java`
- `src/main/java/org/mtr/legacy/resource/LegacyVehicleResource.java`

### map（4 个文件）

- `src/main/java/org/mtr/map/MapCachePath.java`
- `src/main/java/org/mtr/map/MapTileProvider.java`
- `src/main/java/org/mtr/map/MapTileResource.java`
- `src/main/java/org/mtr/map/package-info.java`

### screen（43 个文件）

- `src/main/java/org/mtr/screen/BetaWarningScreen.java`
- `src/main/java/org/mtr/screen/ConfigScreen.java`
- `src/main/java/org/mtr/screen/DashboardListItem.java`
- `src/main/java/org/mtr/screen/DashboardScreen.java`
- `src/main/java/org/mtr/screen/DeleteConfirmationScreen.java`
- `src/main/java/org/mtr/screen/DepotScreen.java`
- `src/main/java/org/mtr/screen/DriverKeyDispenserScreen.java`
- `src/main/java/org/mtr/screen/EyeCandyScreen.java`
- `src/main/java/org/mtr/screen/EyeCandySelectorScreen.java`
- `src/main/java/org/mtr/screen/FakePauseScreen.java`
- `src/main/java/org/mtr/screen/HomeScreen.java`
- `src/main/java/org/mtr/screen/LandmarkScreen.java`
- `src/main/java/org/mtr/screen/LiftCustomizationScreen.java`
- `src/main/java/org/mtr/screen/LiftSelectionScreen.java`
- `src/main/java/org/mtr/screen/LiftStyleSelectorScreen.java`
- `src/main/java/org/mtr/screen/LiftTrackFloorScreen.java`
- `src/main/java/org/mtr/screen/ListSelectorScreen.java`
- `src/main/java/org/mtr/screen/NameColorDataScreenBase.java`
- `src/main/java/org/mtr/screen/PIDSConfigScreen.java`
- `src/main/java/org/mtr/screen/PlatformListSelectorScreen.java`
- `src/main/java/org/mtr/screen/PlatformScreen.java`
- `src/main/java/org/mtr/screen/RailModifierScreen.java`
- `src/main/java/org/mtr/screen/RailStyleSelectorScreen.java`
- `src/main/java/org/mtr/screen/RailwaySignScreen.java`
- `src/main/java/org/mtr/screen/ReloadCustomResourcesScreen.java`
- `src/main/java/org/mtr/screen/RouteListSelectorScreen.java`
- `src/main/java/org/mtr/screen/RouteScreen.java`
- `src/main/java/org/mtr/screen/ScreenBase.java`
- `src/main/java/org/mtr/screen/SidingScreen.java`
- `src/main/java/org/mtr/screen/SignalColorScreen.java`
- `src/main/java/org/mtr/screen/SingleTabBackgroundScreenBase.java`
- `src/main/java/org/mtr/screen/StationExitListSelectorScreen.java`
- `src/main/java/org/mtr/screen/StationListSelectorScreen.java`
- `src/main/java/org/mtr/screen/StationScreen.java`
- `src/main/java/org/mtr/screen/TextCase.java`
- `src/main/java/org/mtr/screen/TicketMachineScreen.java`
- `src/main/java/org/mtr/screen/TrainAnnouncerScreen.java`
- `src/main/java/org/mtr/screen/TrainBasicSensorScreen.java`
- `src/main/java/org/mtr/screen/TrainScheduleSensorScreen.java`
- `src/main/java/org/mtr/screen/TrainSensorScreenBase.java`
- `src/main/java/org/mtr/screen/VehicleSelectorScreen.java`
- `src/main/java/org/mtr/screen/WindowBase.java`
- `src/main/java/org/mtr/screen/package-info.java`

### servlet（8 个文件）

- `src/main/java/org/mtr/servlet/AbstractResourcePackCreatorServlet.java`
- `src/main/java/org/mtr/servlet/ClientServlet.java`
- `src/main/java/org/mtr/servlet/MinecraftOperationProcessor.java`
- `src/main/java/org/mtr/servlet/RequestHelper.java`
- `src/main/java/org/mtr/servlet/ResourcePackCreatorOperationServlet.java`
- `src/main/java/org/mtr/servlet/ResourcePackCreatorUploadServlet.java`
- `src/main/java/org/mtr/servlet/ResourcePackFileHelper.java`
- `src/main/java/org/mtr/servlet/package-info.java`

### widget（32 个文件）

- `src/main/java/org/mtr/widget/BackgroundComponent.java`
- `src/main/java/org/mtr/widget/BetterButtonWidget.java`
- `src/main/java/org/mtr/widget/BetterTextFieldWidget.java`
- `src/main/java/org/mtr/widget/BetterTexturedButtonWidget.java`
- `src/main/java/org/mtr/widget/ButtonComponent.java`
- `src/main/java/org/mtr/widget/ButtonGroupWidget.java`
- `src/main/java/org/mtr/widget/CheckboxComponent.java`
- `src/main/java/org/mtr/widget/ClickableWidgetBase.java`
- `src/main/java/org/mtr/widget/ColorInputComponent.java`
- `src/main/java/org/mtr/widget/ColorPickerGeometry.java`
- `src/main/java/org/mtr/widget/ColorSelectorWidget.java`
- `src/main/java/org/mtr/widget/DeleteConfirmationWidget.java`
- `src/main/java/org/mtr/widget/DirectImageComponent.java`
- `src/main/java/org/mtr/widget/Grouped24HourSlidersComponent.java`
- `src/main/java/org/mtr/widget/ImageComponentBase.java`
- `src/main/java/org/mtr/widget/ListComponent.java`
- `src/main/java/org/mtr/widget/ListItem.java`
- `src/main/java/org/mtr/widget/MapComponent.java`
- `src/main/java/org/mtr/widget/MultiLineTextWidget.java`
- `src/main/java/org/mtr/widget/NumberInputComponent.java`
- `src/main/java/org/mtr/widget/PopupWidgetBase.java`
- `src/main/java/org/mtr/widget/PreviewBoxComponent.java`
- `src/main/java/org/mtr/widget/ScrollPanelComponent.java`
- `src/main/java/org/mtr/widget/ScrollableListWidget.java`
- `src/main/java/org/mtr/widget/ScrollablePanelWidget.java`
- `src/main/java/org/mtr/widget/SignButtonsComponent.java`
- `src/main/java/org/mtr/widget/SignPreviewComponent.java`
- `src/main/java/org/mtr/widget/SlotBackgroundComponent.java`
- `src/main/java/org/mtr/widget/StitchedImageComponent.java`
- `src/main/java/org/mtr/widget/TabGroupWidget.java`
- `src/main/java/org/mtr/widget/TextInputComponent.java`
- `src/main/java/org/mtr/widget/package-info.java`
