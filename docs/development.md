# 开发、真机验证与发布

## 目录布局

- 根目录保留 README、许可证、开发约定和 Gradle 入口；多语言 README 在 `docs/readme/`，数据结构与开发说明在 `docs/`。
- `app/src/main/java/com/zsz/studyassistant/` 保留应用与主状态入口。`data/` 按 `ai`、`backup`、`capture`、`conversation`、`database`、`notebook`、`review`、`tags`、`update`、`storage`、`diagnostics`、`notifications` 归类。
- `ui/` 按 `capture`、`conversation`、`notebook`、`settings`、`appearance`、`components`、`navigation`、`localization`、`home` 归类；统一路由入口仍为 `ui/AppNav.kt`。
- 这些源码子目录仅用于按功能定位文件，Kotlin 包名仍为原来的 `com.zsz.studyassistant.data` / `.ui`，不改变类名、数据库标识、组件注册或序列化身份。`test`、`androidTest`、`integration` 中的相关用例与夹具按相同功能归类，测试类名和调用方式不变。
- `app/schemas/` 保存 Room 结构 JSON；`app/src/main/assets/` 保存运行时离线资源；`app/src/main/res/` 保持 Android 标准资源目录，不混入开发文档。
- `tools/` 保留现有本地检查、发布、签名和可选设备诊断入口，`tools/tests/` 为按需工具用例。`verification.py` 中的历史路径规则只用于旧发布证据读取，不随当前目录改写。
- `.local-notes/` 保存不入库的本机记录及退役文件归档，`history_apk/` 保存已下载核验的发布包。构建缓存、签名配置和用户数据不参与源码目录整理。

`MainViewModel` 负责页面状态与会话业务协调；`data/conversation/ConversationDraftPersistence` 负责草稿调度和磁盘访问，`ui/capture/CaptureFlowController` 管理拍摄框选状态、检测任务及恢复检查点。控制器沿用 ViewModel 生命周期和既有界面入口，框选恢复不会自动请求解题；完整内容保存的冲突与恢复逻辑继续由 `PendingSaveRepository` 负责。

## 日常开发

构建环境固定为 JDK 25、Gradle wrapper 9.8.2、AGP 9.4.0、Kotlin 2.4.21；本地使用 `tools/codex-env.ps1`，CI 使用 Temurin 25。Android Studio 的 Gradle JDK 也应选择 JDK 25。Gradle 分发使用官方地址及 SHA256 校验，无需安装全局 Gradle。

AGP 9 使用内置 Kotlin，Compose/serialization 编译器插件与 Kotlin 同版本；Room 2.8.5 使用 KSP 2.3.12，保留 Java DAO 生成，不改变数据库格式。Java/Kotlin 字节码目标仍为 17，minSdk 36、compileSdk/targetSdk 37、Build Tools 36.0.0。

Room 结构快照由正常 KSP 编译导出至 `app/schemas/com.zsz.studyassistant.data.AppDatabase/<数据库版本>.json`，随源码提交，从当前 DB v10 开始保留。各构建变体共用相同结构；目录不加入应用资源，不打入正式 APK。以后变更表结构时新增对应版本快照并保留旧文件，不手工编造历史版本；无需为导出单独增加测试或 CI 门禁。

当前运行依赖：Compose BOM 2026.09.00、Lifecycle 2.11.0、Navigation Compose 2.10.2、CameraX 1.6.2、Retrofit 3.0.0、OkHttp 5.5.1、kotlinx.serialization 1.12.0、WorkManager 2.12.0（备份与6小时周期更新通知）。离线 KaTeX 0.19.1 的来源与打包说明见 [third-party-assets.md](third-party-assets.md)。版本声明以 `gradle/libs.versions.toml` 为准。

GitHub Actions 直接使用官方 Google Maven、Maven Central 与 Gradle Plugin Portal；国内本地开发保留原镜像优先配置。定向 JVM 的实际任务为 `testIntegrationUnitTest`（AGP 9 对应 `testBuildType = integration`），仍由 `-TestClass` 显式选择，不启动设备。

Develop 或 codex/ 功能分支开发 → 本地最小必要检查 → 必要时 Beta 真机/ADB → 相关修正与补验 → 规范合并 main → 一次 CI 构建 → 本地签名、Release、下载校验。

每次先说明具体风险及最小验证范围。不要给每项功能增加新测试、流程或门禁；没有必要就不测。保留对可靠性的要求，但不以全套测试替代判断。同输入结果复用；改动只补相关部分。失败不扩大范围、不默认重跑、不改断言求绿色。测试失败、应用错误、环境问题和未确定原因分别报告。

## 本地入口

```powershell
# 默认仅diff/staged diff/敏感内容扫描；不会加载Android环境
./tools/check-project.ps1
# 先查看范围，不执行
./tools/check-project.ps1 -Plan
# 仅确有必要时选择其中一种；单测本身包含所需编译
./tools/check-project.ps1 -Compile
./tools/check-project.ps1 -TestClass 'com.zsz.studyassistant.data.UpdateCompatibilityTest'
```

无 Full 模式、无自动选全套。已有JVM方法按需调用；不要在本地跑后又在CI跑。Gradle沿用自身增量结果，日志留`.local-notes/verification/local`，不新建跨环境证据状态机。

验证选择：纯文案/布局通常差异检查及必要人工查看；纯计算或版本/解析等高风险逻辑选相关JVM方法；数据库/备份变动选相关数据回归或SQLite专项，不每次调用全部数据测试。工具变动只运行相关离线工具用例，不触发应用测试。现有app/src/test、androidTest是可选诊断资料，不是发布必跑清单。

## 真机与Beta

相机缩放、厂商通知胶囊、触摸振动、返回动画、后台行为优先用户真机。开发者提供安装包、构建/源码身份、3—5条操作和预期结果；用户反馈后再定位，避免自行搭建长UI自动化。

Beta可从开发分支本地按需构建一次：`./gradlew.bat assembleRelease --console=plain`，再用现有`tools/sign-release.ps1 -InApk app/build/outputs/apk/release/app-release-unsigned.apk`签名。这只生成可交付测试包，不自动发GitHub Release、不安装手机。版本号/versionCode、渠道与分发方式按当次安排：覆盖安装要原证书，正式后继versionCode不得低于已交付Beta；公开测试版须明确标prerelease且不得latest。公开Beta发布若需离开main的独立路径，应当次明确安排，不能悄悄合并实验分支。

后续公开 Beta 按目标正式版依次编号，例如 `X.Y.Z_beta1`、`X.Y.Z_beta2`。每轮使用独立标签和递增的 versionCode，正式版使用无后缀的 `X.Y.Z` 及更高的 versionCode。APK 的 versionName、文件名和 Git 标签均保留完整编号；应用内显示和跳过也区分各轮。更新清单继续写数值基础版本和 beta 渠道，兼容旧客户端。历史 `_beta` 包和标签继续可读。

用户明确要求从 Develop 公开 Beta 时，在已推送的干净 Develop 执行 `./tools/release.ps1 start --version X.Y.Z_beta1 --channel beta`，随后按同一 run 执行 status/publish。该 Release 标为 prerelease 并保持现有 latest。已发布的正式标签固定在原提交。历史同版本附件替换仅用于用户明确安排的例外，通过 `start --replace`、`status`、`replace --run` 核对来源与下载摘要，并保留原标签和渠道。

用户允许后使用指定设备的有线/无线ADB；配对/连接参数由用户实际设备提供。不自动选择USB手机。定位只取相关日志、进程/通知状态；不清数据、不卸载、不导出真实题库、Key或全量日志。手机反馈是该包该设备的证据，不夸大到全部机型。Beta之后修改相关代码，需要相关补验；纯打包签名不重跑行为测试。

## 可选模拟器诊断

模拟器不参与默认本地/在线/发布流程。确需可重复的Android系统边界验证且真机不合适时，说明理由并选最少类/方法：

```powershell
./tools/run-android-tests.ps1 -Serial emulator-5554 -TestClass '完整类名#方法名' -UseExistingApks
```

必须指定已确认的模拟器；测试只用隔离包和合成数据。不因新增功能或未知文件自动全量；`-All`仅用户明确要求的专项。失败后复用成功构件，定位失败方法，不重新执行不相关用例。

## 合并与构建

应用内版本记录位于 `app/src/main/assets/changelog/{zh,en,ja,ko}.txt`（UTF-8）。发版时在各文件顶部添加新版本块，并与旧记录留一行空行。繁体中文在读取简体记录时转换。

本地必要验证/用户反馈完成后再合并main。提交含feat:/fix:/build:/ci:等前缀；合并冲突改变运行代码时只验证受影响部分。

```powershell
git switch main
git merge --no-ff Develop -m 'feat: describe the completed change'
git push origin main
./tools/release.ps1 start --version VERSION
./tools/release.ps1 status --version VERSION --run RUN_ID
./tools/release.ps1 publish --run RUN_ID
```

也可快进到已有规范提交。正式版 start 只接受已推送的干净 main；显式 `--channel beta` 也接受已推送的干净 Develop，不执行测试或隐式合并。GitHub Actions仅手动触发、一个build job；Develop 可在用户要求时构建一次 development-build，成功后规范合并 main，合并不自动重跑。正式发布候选仍只能由 main 生成：核对请求SHA/main与敏感文件 → JDK/Gradle → assembleRelease → APK身份与摘要 → 上传候选。无Windows工具job、JVM/SQLite/设备job、预门禁、晋级等待或最终测试gate。push/PR/tag不重复触发，标题使用版本与用途。构建失败先定位，不盲派发第二轮。

schema3候选清单记录包名、版本、minSdk/targetSdk、commit、构建时间、SHA、签名后保持的代码资源摘要与预期证书，不含JVM/设备通过结论。旧schema1/2仅保留历史读取兼容；不要求为新版本下载旧测试证据，不对新流程回退全量。

## 签名与发布底线

只使用原自有证书/lineage；CI不读取签名材料。候选必须非debuggable且未签名，运行成功、对应发布分支与本地源码吻合；签名后重新核对身份、原证书及代码资源摘要。发布保留不可变tag、正式/beta渠道与最低系统要求、versionCode、更新清单、最终公开附件真实下载SHA和history_apk归档。普通发版start→status→publish，verify只是按需诊断，不固定多执行一次。签名、发布、归档不触发测试。

同版本替换仍须用户明确安排，使用start --replace与replace --run，保留原标签；不默认替换既有Release。历史精确来源检查仅用于此类替换和历史恢复，不作为每次新版本前置。

普通正式更新仍是默认值。只有用户明确指定强制更新时，才在首次 `start` 加 `--mandatory`；该选项只允许 stable，写入更新清单的 JSON 布尔 `mandatory: true`，并在最终 APK 的 `.apk` 前添加 `-ENF`，不改 applicationId、versionName 或签名字节。Release 最后一条在最低系统要求与正式版属性之后追加“本次更新极其重要，是强制更新”。决定保存在本地发布状态中；恢复同一 run 时沿用，不能给既有发布状态补改强制属性。旧清单缺省为普通更新，Beta 不强制。

需要差分包时，在首次 `start` 显式指定 `--delta-base history_apk/StudyAssistant-<版本>-<构建时间>.apk`。脚本核对旁边的 `<APK文件名去扩展名>-update-manifest.json` 与原始完整签名 APK 的 SHA256、尺寸和正式渠道，并固定这份基线；最终候选原证书签名后生成 `.gdiff.gz`。格式采用 [GDIFF v4](https://www.w3.org/TR/NOTE-gdiff-19970901) 的复制/字面数据命令再 gzip 压缩，客户端不需要 native ABI 库。发布前本地重建并逐字节核对最终 APK；不重新压缩 APK、不重新签名。差分不小于完整包时停止该步骤，不发布无收益的差分。

更新清单的可选 `deltas` 数组记录 `format: "gdiff4-gzip"`、`baseSha256`、`baseSize`、`baseVersionCode`、`patchName`、`patchSha256`、`patchSize`；最终结果沿用顶层 `apkSha256` / `apkSize`，下载地址由同 Release 的完整包地址与安全的补丁文件名派生。当前工具每次生成一份指定基线差分。新客户端仅在已安装 APK 的原始 SHA256 和尺寸完全匹配时尝试差分；失败后回退完整包，用户取消则停止。旧客户端继续使用完整 APK。完整包、清单和差分一起上传，并分别真实下载 SHA 核验和归档到 `history_apk`；恢复发布复用已验证差分，不变更基线或既有资产。

明确使用 `start --replace` 同版本替换时，差分基准可选择原构建，允许 `versionCode` 相同；普通新版本仍要求基准 `versionCode` 更低，禁止降级或生成相同字节的补丁。应用按安装包 SHA 和构建时间区分同版本的新构建，版本号不变不会绕过差分及完整包的校验。

## 网络与隐私

源码/分支用Git；状态/日志/Release优先GitHub连接器，能力不足用gh/API。网络失败先检查远端是否已成功；必要时仅一次进程级HTTP/1.1重试，不修改全局代理、不强推。测试/签名/发布状态均存.local-notes；真实题库、Key、签名材料、手机日志不得提交或上传CI。普通状态查询不高频轮询。

## 流程维护边界

开发中不自行扩大必跑测试范围。新增测试/流程必须有具体风险与收益，优先已有短用例或真机步骤；不重复覆盖，不维护为了维持复杂流程而新增的整套协议。退役自动规划和工具测试已在.local-notes/retired-files/20261030-test-flow本地归档。只保留与当前发布/本地入口直接相关的小型工具回归，按需本地运行，CI不调用。

JDK 25 的原生访问警告可能写入 stderr。本地 PowerShell 签名脚本按 apksigner 的退出码判断成功，同时保留证书、v3 与内容摘要验证，不把警告误当作签名失败。只修改文档或本地签名脚本时不重建已经核验的应用候选包。
