# libs 目录

本目录用于放置 **YMTR（Yomi's Minecraft Transit Railway，MTR 3.x）的开发环境 jar**，
作为本补丁模组的编译依赖。jar 文件已被 `.gitignore` 排除，不会进入版本库。

## 获取方式：从 YMTR 源码构建

```bash
git clone https://github.com/yomi-china/Yomis-Minecraft-Transit-Railway.git
cd Yomis-Minecraft-Transit-Railway
./gradlew --configure-on-demand :fabric:jar      # 需要 JDK 17
```

> `--configure-on-demand` 只配置 `:common` 与 `:fabric`，跳过 `:forge` 项目的
> MCP/SRG 处理管线（该管线与 Fabric 产物无关，但耗时较长）。

`jar` 任务本身只打包 `:fabric` 子项目的类；把 `:common` 与 `:fabric` 的类合并为
一个 dev jar（即 Mojang 命名、可直接用于本项目的 `compileOnly` 依赖）：

```bash
mkdir -p <本项目>/libs
jar cf <本项目>/libs/MTR-fabric-1.20.1-3.6.5-dev.jar \
    -C <YMTR>/common/build/classes/java/main . \
    -C <YMTR>/fabric/build/classes/java/main .
```

本项目已按 `libs/*.jar` 全量引入，文件名可任取。

## 说明

- 本补丁只依赖 YMTR 的公开类与 Mixin 注入，不修改、不内嵌、不再分发任何 YMTR
  代码；此 jar 仅用于本地编译（YMTR 为 ARR 许可，请勿随本模组分发该 jar）。
- 编译目标为 YMTR 3.6.x（1.20.1）；`Rail` 的经典几何字段与查询方法自 MTR 3.0 起
  保持稳定，其他 3.x 小版本预计同样可用。
- 需要引用到的 YMTR 公开 API：`mtr.data.Rail` / `mtr.data.RailwayData` /
  `mtr.data.RailAngle` / `mtr.packet.PacketTrainDataGuiServer` /
  `mtr.block.BlockNode` / `mtr.client.AnteRailCompat` / `mtr.CreativeModeTabs`。
