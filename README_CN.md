# ReForge

中文 | [English](README.md)

尝试在 Minecraft Forge 上运行 NeoForge 模组的实验性兼容层，包含加载、字节码转换、API 桥接、事件与资源适配。

**这是一个 vibecoding 作品。** 大量代码由 AI 辅助生成或修改，维护者负责验证与维护。编译通过不代表模组兼容，也不代表长期稳定。

## 玩家

当前基线：**Minecraft 1.21 / Forge 51.0.33 / Java 21**。不支持 Forge 1.20.1，尚未确认 Minecraft 1.21.1 支持。

1. 使用独立游戏实例，备份存档。
2. 从源码构建，将唯一安装包 `build/libs/reforge-1.0.0.jar` 放入 `mods/`。
3. 加入目标 NeoForge 模组及其依赖，检查启动日志和实际玩法。

安装包内嵌运行时与 MixinExtras。`build/intermediates/jars/` 中的其他 JAR 是构建中间产物或测试夹具，不需要安装。

启动保留原始 NeoForge JAR，在 `.reforged/discovery/` 生成发现副本。手动 `patchNeoForgeMods` 会替换输入并保留 `.neoforge-original` 备份。模组作者声明与内部 Mod ID `reforged` 不兼容时，该模组会退出桥接加载。

已知限制：

- 不支持附件自动网络同步。
- 真实联机、Jade 玩法、复杂整合包及长期存档运行尚未完成验证。
- 内嵌依赖内容冲突、必要加载或注册失败会阻断启动。

向 [Issues](https://github.com/Mai-xiyu/ReForge/issues) 提交可复现的桥接故障，附准确版本、复现步骤、日志与客户端/服务端范围，并删除隐私信息。在原生 NeoForge 也能复现时，再向原模组作者报告。

## 开发者

使用 JDK 21 与 Gradle Wrapper：

```sh
git clone https://github.com/Mai-xiyu/ReForge.git
cd ReForge
./gradlew build verifyReleaseJar
```

Windows 使用 `.\gradlew.bat`。安装包及 SHA-256 文件位于 `build/libs/`。

| 命令 | 用途 |
| --- | --- |
| `./gradlew test` | 契约与失败路径回归 |
| `./gradlew runClient` | 通过安装包启动流程运行开发客户端 |
| `./gradlew runClient -PreforgedRunDir=build/smoke/client -PclientSmoke=true` | 最小模组夹具到达标题界面并退出 |
| `./gradlew runGameTestServer -PreforgedRunDir=build/smoke/gametest -PpersistencePhase=write` | 附件测试与持久化样本写入 |
| 同一命令改为 `-PpersistencePhase=read` | 新进程读取同一世界中的样本 |

专服需要接受 Minecraft EULA。启动服务与运行时使用隔离包名，以满足 Forge 发现流程和 Java 模块边界；对玩家仍交付一个文件。内部 ID、包名和缓存路径保留 `reforged`，避免破坏现有契约。

[稳定化记录](docs/STABILIZATION.md) 包含已实施修复、测试证据、历史 Issue、功能添加与后续验收。后续重点为真实两端联机、附件生命周期、JAR 事务边界、内嵌依赖版本选择与性能基线。

## 来源与许可证

基于 [ReForged](https://github.com/Arc-Stuido/ReForged) 延续开发，保留源码历史与已有版权声明。许可证为 [LGPL-2.1-only](LICENSE)。ReForge 是独立实验项目。
