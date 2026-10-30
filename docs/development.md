# 开发、真机验证与发布

## 日常开发

构建环境固定为 JDK 25、Gradle wrapper 9.8.2、AGP 9.4.0、Kotlin 2.4.21；本地使用 `tools/codex-env.ps1`，CI 使用 Temurin 25。Android Studio 的 Gradle JDK 也应选择 JDK 25。Gradle 分发使用官方地址及 SHA256 校验，无需安装全局 Gradle。

AGP 9 使用内置 Kotlin，Compose/serialization 与 Kotlin 同版本；Room 2.8.5 使用 KSP 2.3.12，保留 Java DAO 生成，不改变数据库格式。Java/Kotlin 字节码目标仍为 17，minSdk 36、compileSdk/targetSdk 37、Build Tools 36.0.0。

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

用户允许后使用指定设备的有线/无线ADB；配对/连接参数由用户实际设备提供。不自动选择USB手机。定位只取相关日志、进程/通知状态；不清数据、不卸载、不导出真实题库、Key或全量日志。手机反馈是该包该设备的证据，不夸大到全部机型。Beta之后修改相关代码，需要相关补验；纯打包签名不重跑行为测试。

## 可选模拟器诊断

模拟器不参与默认本地/在线/发布流程。确需可重复的Android系统边界验证且真机不合适时，说明理由并选最少类/方法：

```powershell
./tools/run-android-tests.ps1 -Serial emulator-5554 -TestClass '完整类名#方法名' -UseExistingApks
```

必须指定已确认的模拟器；测试只用隔离包和合成数据。不因新增功能或未知文件自动全量；`-All`仅用户明确要求的专项。失败后复用成功构件，定位失败方法，不重新执行不相关用例。

## 合并与构建

本地必要验证/用户反馈完成后再合并main。提交含feat:/fix:/build:/ci:等前缀；合并冲突改变运行代码时只验证受影响部分。

```powershell
git switch main
git merge --no-ff Develop -m 'feat: describe the completed change'
git push origin main
./tools/release.ps1 start --version VERSION
./tools/release.ps1 status --version VERSION --run RUN_ID
./tools/release.ps1 publish --run RUN_ID
```

也可快进到已有规范提交。start只接受已推送的干净main，不执行测试或隐式合并。GitHub Actions仅手动触发、一个build job；Develop 可在用户要求时构建一次 development-build，成功后规范合并 main，合并不自动重跑。正式发布候选仍只能由 main 生成：核对请求SHA/main与敏感文件 → JDK/Gradle → assembleRelease → APK身份与摘要 → 上传候选。无Windows工具job、JVM/SQLite/设备job、预门禁、晋级等待或最终测试gate。push/PR/tag不重复触发，标题使用版本与用途。构建失败先定位，不盲派发第二轮。

schema3候选清单记录包名、版本、minSdk/targetSdk、commit、构建时间、SHA、签名后保持的代码资源摘要与预期证书，不含JVM/设备通过结论。旧schema1/2仅保留历史读取兼容；不要求为新版本下载旧测试证据，不对新流程回退全量。

## 签名与发布底线

只使用原自有证书/lineage；CI不读取签名材料。候选必须非debuggable且未签名，运行成功、main与本地源码吻合；签名后重新核对身份、原证书及代码资源摘要。发布保留不可变tag、正式/beta渠道与最低系统要求、versionCode、更新清单、最终公开附件真实下载SHA和history_apk归档。普通发版start→status→publish，verify只是按需诊断，不固定多执行一次。签名、发布、归档不触发测试。

同版本替换仍须用户明确安排，使用start --replace与replace --run，保留原标签；不默认替换既有Release。历史精确来源检查仅用于此类替换和历史恢复，不作为每次新版本前置。

## 网络与隐私

源码/分支用Git；状态/日志/Release优先GitHub连接器，能力不足用gh/API。网络失败先检查远端是否已成功；必要时仅一次进程级HTTP/1.1重试，不修改全局代理、不强推。测试/签名/发布状态均存.local-notes；真实题库、Key、签名材料、手机日志不得提交或上传CI。普通状态查询不高频轮询。

## 流程维护边界

开发中不自行扩大必跑测试范围。新增测试/流程必须有具体风险与收益，优先已有短用例或真机步骤；不重复覆盖，不维护为了维持复杂流程而新增的整套协议。退役自动规划和工具测试已在.local-notes/retired-files/20261030-test-flow本地归档。只保留与当前发布/本地入口直接相关的小型工具回归，按需本地运行，CI不调用。
