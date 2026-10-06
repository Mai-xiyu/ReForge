# 稳定化记录与后续验收

基线：Minecraft 1.21 / Forge 51.0.33 / Java 21。本文对应用户提供的独立工程评估 R1–R8，区分已实现、已验证和剩余验收。项目展示名与仓库改为 ReForge，内部 Mod ID 保留 reforged。源码仓库为 https://github.com/Mai-xiyu/ReForge；历史 Issue 编号仍属于原 ReForged 仓库。

## 第一阶段：正确性与数据安全

| 项目 | 根因与改动 | 验证范围 |
| --- | --- | --- |
| R1 JAR 管理 | 旧备份覆盖同名新输入、删除后恢复旧 Mod。改为识别占位包，使用新输入，摘要归档旧备份，锁内清理临时文件，替换前核验源摘要；启动使用缓存副本，避免 Windows 下替换已打开 JAR | 同名升级、主动删除、丢失备份、中断临时文件、作者排除、并发预处理、打开原件时生成缓存 |
| R2 初始化 | 构造或关键步骤失败后继续启动。构造、配置、注解扫描、事件订阅和早期预处理失败向上传播；不支持的构造器明确失败 | 构造已有副作用后失败、不支持构造器、损坏类文件、事件回调异常及上下文恢复 |
| R3 附件 | 默认查询递归、哈希身份碰撞、两套存储。游戏对象统一进入 NeoForge 附件门面，保留弱身份后备存储，查询不创建默认值；注册 ID、序列化复制及保存钩子接入 | 身份隔离、查询、复制、未知 NBT 保留、编解码异常；实体、方块实体、区块、维度运行时往返 |
| R4 网络 | registrar 选项丢失、错误方向或阶段仍进入业务处理。保存版本、phase、flow、optional、线程契约；解码前及主线程排队后校验 | 选项组合、版本、方向、阶段、实际 receive 解码和调度路径；尚无真实两端连接 |
| R5 转换与 Mixin | 必要转换失败返回原字节、提取失败生成不完整占位包。失败显式传播，保留 required/defaultRequire，缺失闭包或配置拒绝预处理 | 非法字节码、TCCL 恢复、必要 Mixin 缺失及占位包生成阻断 |

## 第二阶段：契约与确定性

| 项目 | 改动 | 验证与限制 |
| --- | --- | --- |
| R6 类加载与缓存 | TCCL 用 finally 恢复；内容 SHA-256、缓存版本、完成标记与锁；JiJ 内容去重，同坐标或无元数据同名不同内容明确拒绝 | 同名同尺寸同时间戳内容更新、损坏 JiJ、冲突的正反输入顺序、相同内容去重；未实现 Maven 版本范围选择 |
| R7 事件 | fallback 按总线、owner、优先级、取消状态及父类匹配；注销真实 wrapper；字节码 helper 使用指定总线；回调失败不伪装成功 | Forge 实际路径与 fallback 的取消、优先级、继承、隔离、注销、异常；混合路径的全局顺序仍需差分验证 |
| R8 版本与依赖 | TOML 结构解析；required/optional/incompatible/discouraged、side、版本、重复 ID、排序与循环预检；固定当前 MC/Forge 范围；先提取 JiJ，再预检与排序构造 | 缺依赖、错误版本、错误 side、循环及非法元数据。NeoForge API 范围保留 UNKNOWN，不代表兼容认证 |

包引用分析仅报告引用覆盖，不验证逐个字段、方法描述符或行为。0 引用或分析失败不能作为稳定兼容证据。

## 第三阶段：发布门禁与运行证据

原发布结构把早期服务与普通 Mod 放在同一 JAR：Forge 扫描早期服务后不再发现普通 Mod；重复发现又引入模块包冲突。现在 `releaseJar` 生成单文件安装包：

```text
reforge-1.0.0.jar
  org/xiyu/reforged/bootstrap/          隔离的启动服务与转换实现
  META-INF/services/                  TransformationService、IModLocator
  reforged/                           启动映射资源
  META-INF/reforged/reforged-runtime.jar
    META-INF/mods.toml
    reforged.mixins.json
    META-INF/jarjar/                   MixinExtras 与元数据
```

运行时按内容摘要提取至 `<gameDir>/.reforged/runtime/`。`build/libs/` 仅输出安装包与摘要；`jar`、`jarJar`、GameTest 和兼容夹具 JAR 统一放在 `build/intermediates/jars/`。GameTest 类通过单独测试包嵌入，不进入发布包。

Windows 实测发现 Forge 早期服务扫描会打开原始 NeoForge JAR，原地替换报 AccessDenied。因此启动在 `.reforged/discovery/` 生成副本，并通过定位器交给 Forge；缓存身份同时包含输入与转换器制品摘要。原始 NeoForge-only 输入加入 Forge 文件夹扫描排除名单，避免被当成非法 Forge Mod。该适配反射访问 Forge 51.0.33 的 `ModDirTransformerDiscoverer.found`，依赖已固定版本；字段结构变化会明确阻断启动。手动补丁命令仍提供原地转换，历史占位包继续使用原件备份。

`verifyReleaseJar` 校验外层服务声明、包隔离、映射与许可证，以及内层 Mod 描述、Mixin 配置、MixinExtras 和测试类隔离，生成 SHA-256 文件。归档固定条目顺序与时间戳，manifest 移除构建时间。

CI 已配置编译、JUnit、发布结构校验、强制重打包摘要比较，以及同一独立世界的写入/重启读取测试；保留测试报告和日志。JUnit 0 执行数和 GameTest 未执行、旧日志或不足 7 个通过都使构建失败。远端 CI 结果以 GitHub Actions 为准，本地记录不代表远端通过。

`src/compatibilityTest/` 提供最小 NeoForge 模组及其内嵌依赖，走原始 JAR → 缓存占位包 → Forge 发现 → JiJ 提取 → 依赖排序 → 构造 → common setup 回调的完整链路，GameTest 断言两者实际初始化及事件回调。该夹具不进入发布包。`-PfixtureFailure=true` 在注册监听器后主动抛出构造异常，CI 要求启动失败且日志包含该明确原因。

本地 Windows 客户端也执行了最小夹具，自动断言到达 `TitleScreen`、两模组初始化及 common setup 回调完成后正常退出。最小夹具测试没有进入世界；Jade 单独测试见下文；本次加了 `--offline -x downloadAssets`，日志存在原版贴图/语言资源缺失警告，不能作为画面正确性验证。基础命令为 `./gradlew runClient -PreforgedRunDir=build/smoke/client -PclientSmoke=true`，结果写入运行目录的 `client-smoke-result.txt`。

复现命令（PowerShell；已有可用依赖时可追加 `--offline`）：

```powershell
.\gradlew.bat build verifyReleaseJar --no-daemon
.\gradlew.bat runGameTestServer --no-daemon -PreforgedRunDir=build/smoke/gametest -PpersistencePhase=write -x downloadAssets
.\gradlew.bat runGameTestServer --no-daemon -PreforgedRunDir=build/smoke/gametest -PpersistencePhase=read -x downloadAssets
```

专服需要该独立目录中的 Minecraft EULA 接受记录。写入和读取必须使用同一目录，读取阶段不能创建新世界或先覆盖样本。

本次本地验证记录（2026-10-06，Windows / JDK 21.0.10）：

| 验证 | 实际结果 | 本地证据 |
| --- | --- | --- |
| JUnit | 56 执行，0 失败，0 跳过 | `build/reforge-build.log`、`build/test-results/test/` |
| 新建专服世界写入 | 7 个 required GameTest 通过 | `build/smoke/reforge-write.log` |
| 退出后新进程读取同一世界 | 7 个 required GameTest 通过，维度/实体/方块实体/区块附件保留 | `build/smoke/reforge-read.log` |
| 必要构造器主动失败 | 启动被阻断，明确报告 `fixture-required-construction-failure` | `build/smoke/reforge-construction-failure.log` |
| Jade 15.1.6 客户端 | 原生 Mixin、插件初始化、标题界面及 JadeFont 接口身份/调用通过；未验收世界内玩法 | `build/smoke/jade-verified.log`、`build/smoke/jade-verified/client-smoke-result.txt` |
| 客户端最小夹具 | 标题界面、构造、common setup 与 GUI 注册回调断言通过，自动退出 | `build/smoke/reforge-client.log`、`build/smoke/reforge-client/client-smoke-result.txt` |
| 制品与重复打包 | 结构门禁通过；同源码强制重打包 SHA-256 一致 | `build/reforge-reproducibility.log`、`build/libs/reforge-1.0.0.jar.sha256` |
| 文档/工作流 | `git diff --check` 通过，工作流路径已同步；远端 CI 结果单独确认 | 本地命令结果 |

当前安装包 SHA-256：`e9fdbea98a364def65e99a9ffc9946052f7b3caa554c8d91ddcab7ce9fe8a444`。这是当前工具链同源码重打包的结果，尚未证明跨操作系统或独立干净构建环境的字节一致性。客户端/专服均通过 ForgeGradle userdev 启动并使用启动包定位流程，独立 Forge 安装器环境仍需另验。

## Issue 修复

先前核查记录为 2026-10-03；原仓库本次直连超时，经 `127.0.0.1:10808` 代理访问返回 404，未能复核现状。以下是原仓库历史 Issue 的处理记录，不对应新仓库的编号。

| Issue | 处理 | 尚需验收 |
| --- | --- | --- |
| #5 Jade 类加载器可见性 | 事件代理使用桥接类加载器，指定共享接口 parent-first；移除强行注入缺失接口及字体空实现的旧补丁，使用 Jade 自带 Mixin | Jade 15.1.6+neoforge 已通过初始化与字体接口身份冒烟；继续世界内 tooltip、实体访问及注册/注销 |
| #7 许可证 | LGPL-2.1-only 元数据，发布包包含 LICENSE | 发布时核对源码与制品对应关系 |
| #8 作者主动排除 | 恢复原件、跳过补丁及桥接加载，已有回归 | 新增作者声明格式时补充 fixture |
| #9 Forge 1.20.1 | 维持此前拒绝与当前版本边界 | 无实现计划，不扩大当前承诺 |

PR #6 中的 Shadow 打包不能替代运行时字节码转换；当前采用启动/运行时隔离解决制品发现问题。

## 功能添加与后续完善

以下为后续验收；已完成的子项在表内说明，其余不标记为已实现：

| 顺序 | 工作 | 完成标准与取舍 |
| --- | --- | --- |
| 1 | 第三方客户端与 Jade 回归 | 已有最小夹具标题界面冒烟；继续对固定版本组合验证世界进入、tooltip、渲染和退出；必要客户端注册失败已阻断启动并完成最小故障注入，继续验收其他注册与渲染路径；失败定位到公共契约或限定版本补丁 |
| 2 | 真实联机与原生差分 fixture | 客户端/专服验证 PLAY、CONFIGURATION、COMMON、MAIN/NETWORK；错误版本/方向/阶段/缺失必要通道拒绝；原生 NeoForge 与桥接环境比较事件轨迹和线程 |
| 3 | 扩展持久化与负载回归 | 已补充复制失败时内部容器不部分提交、未选目标值保留回归；继续真实死亡复制、维度转换、区块卸载重载、移除 Mod、未知附件、解码失败与多次重启；断言状态而非仅检查无崩溃 |
| 4 | JAR 完整事务与缓存完整性 | 已实现 discovery v3 完成记录，核验输入/转换器/输出摘要及原件备份；缓存损坏、记录缺失和备份异常可重建。继续对每个替换边界注入中断，不能覆盖用户更新或恢复主动删除的 Mod |
| 5 | JiJ 版本选择 | 按坐标和所有需求范围选择唯一版本；交集为空时拒绝；选择不依赖扫描顺序。当前拒绝内容冲突，行为保守但确定 |
| 6 | 自动附件网络同步 | 跟踪、取消跟踪、默认值、删除、过滤及 codec 契约都通过两端断言后再开放 `Builder.sync`；当前调用明确拒绝 |
| 7 | 逐成员二进制验证与转换规则清单 | 解析 class/field/method 描述符，区分 PASS/FAIL/UNKNOWN；规则记录版本、结构、命中数、前后置条件及必要性 |
| 8 | 长期运行与性能基线 | 固定负载记录启动/转换时间、tick/帧时间分布、堆保留量、监听器/队列数量；确认增长有界，再针对热点优化 |

已实现复制与持久化不等于所有第三方附件格式兼容。已通过专服测试不等于大型整合包、真实联机或长期服务器稳定；当前发布版本号仍为 1.0.0，应以制品摘要区分历史包与本地构建。

## 本轮追加实现

- 启动 discovery 缓存升级到 v3。锁内验证完成记录、生成包和原件备份摘要；无效缓存从当前原件重建，完成记录最后替换，源在处理后再次校验。回归覆盖保留标记的损坏包、缺失/损坏记录与缺失/损坏备份。完整性核验增加与 JAR 字节量成正比的读取成本，换取不复用损坏缓存；这不是恶意本机篡改的认证机制。
- Forge GameData 与客户端 GUI、菜单、命名渲染类型、缓冲区、物品装饰和维度过渡注册失败显式向上传播。最小客户端夹具新增 GUI 回调正向断言与 `-PfixtureClientFailure=true` 负向注入。
- 负向客户端实际进入 Forge 加载错误状态，日志包含 `Required GUI layer registration failed` 与 `fixture-required-client-registration-failure`，未产生标题界面成功记录。测试关闭该错误窗口后，Gradle 冒烟门禁失败；不能据此声称客户端会自动退出。证据：`build/smoke/reforge-client-failure.log`。
- 附件复制先收集所有结果，再写入目标容器；后续复制处理器失败不提交前面的值。此保证限于容器写入，不回滚自定义处理器自行产生的副作用；并发访问仍遵循游戏线程约束。额外暂存空间为 O(k)，k 为选中的附件数。
- 中英文 README 重写，明确 vibecoding 开发方式；发布目录只保留 `reforge-1.0.0.jar` 与摘要。源码历史、许可证、内部 ID 和作者排除契约保留。

## Jade 客户端实测

使用 [Modrinth 固定版本 eNY0Rg8n](https://modrinth.com/mod/jade/version/eNY0Rg8n) 的 `Jade-1.21-NeoForge-15.1.6.jar`。API 标注支持 1.21，下载已校验 API 提供的 SHA-512；没有把本次输入 JAR 加入仓库或安装包。

实际复现了旧 `JadeEntityAccessMixin` 对 `Entity` 注入不存在的 `snownee.jade.mixin.EntityAccess`，导致 Mixin 的 `IllegalClassLoadError`。该 Jade 版本不包含这个类。现已删除该补丁及字体空实现，保留提取的 Jade 原生 Mixin；客户端到达标题界面、插件初始化和 payload 注册完成。

`-PjadeSmoke=true` 额外断言 NeoMod 类加载器中的 `JadeFont` 与原版 Font 对象身份兼容，并调用两项 glint 接口方法。该测试证明初始化与接口调用，不证明世界内 tooltip、视觉 glint 效果或真实联机。原版资源仍有缺失警告。

测试运行目录为 `build/smoke/jade-verified/`，日志与结果为 `build/smoke/jade-verified.log`、`build/smoke/jade-verified/client-smoke-result.txt`。将上述固定版本 JAR 放入该目录的 `mods/` 后运行：

```powershell
.\gradlew.bat runClient --no-daemon -PreforgedRunDir=build/smoke/jade-verified -PclientSmoke=true -PjadeSmoke=true
```

开发 Copy 任务已关闭目录状态跟踪，避免 Gradle 在首次使用新的 build 子目录时把用户预装 Mod 当成过期输出清理。新建测试目录中预先放入 Jade，再执行任务，已验证原 JAR 保留并实际加载；Jade 检查开关也能防止只运行最小夹具时误报成功。

远端 Linux CI：提交 `8a6d4452` 的[构建 37445460120](https://github.com/Mai-xiyu/ReForge/actions/runs/37445460120) 已通过编译、JUnit、制品校验、重复打包与专服写入/重启读取和构造失败门禁。该运行不包含后续 Jade 补丁移除；后续提交由新的 CI 运行单独验收。
