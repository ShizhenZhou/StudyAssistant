# 单主线与两个构建变体

所有开发与兼容适配在同一主线维护。日常使用 Develop 或 codex/ 功能分支，完成后合入 main；旧 Compatible 分支已停用并删除；历史提交已包含在 main 中，历史 Release 和标签继续保留。

| Gradle flavor | 发行身份 | 系统范围 | 配置与适配目录 | 正式包名 |
|---|---|---|---|---|
| modern | main | Android 16+，API 36+ | config/distributions/main.properties、app/src/distributions/main/ | SA-…apk / SA-ENF-…apk |
| compat | compat | Android 9–15，API 28–35 | config/distributions/compat.properties、app/src/distributions/compat/ | SA-…_Compat-…apk / SA-ENF-…_Compat-…apk |

共同业务、UI、数据模型与渲染资源在 app/src/main/。差异目录保留数据库适配和各自四语言更新记录。modern 避开 Gradle 保留的 main source set，但对外身份仍是 main。原 applicationId、签名、DB v11 与 Bak v4 不变。

## 构建

按需要选择一个任务，不要求每次构建两个：

```powershell
./gradlew.bat assembleModernRelease --console=plain
./gradlew.bat assembleCompatRelease --console=plain
```

未签名产物分别位于 app/build/outputs/apk/modern/release/app-modern-release-unsigned.apk、app/build/outputs/apk/compat/release/app-compat-release-unsigned.apk。正式签名仍使用本地原证书和 lineage。

Android Studio 在 Build Variants 中选择 modernDebug 或 compatDebug。不再使用分支选择器或 -Pdistribution；每个变体有独立生成资源、BuildConfig、构建时间与 APK 输出目录。

## 最小验证

默认 tools/check-project.ps1 仍只检查差异与敏感内容；确有必要才指定 -Compile 或 -TestClass，可加 -Distribution compat。主线对应 compileModernDebugKotlin / testModernIntegrationUnitTest，兼容版对应 compileCompatDebugKotlin / testCompatIntegrationUnitTest，不额外执行另一变体。

现有可选设备脚本支持 -Distribution main/compat，仍须确认目标和授权，不因变体增加而自动运行。

## 独立发布

两种正式版均使用干净、已推送的 main；明确安排的 Beta 可用 Develop 和 --channel beta：

```powershell
./tools/release.ps1 start --version <版本>
./tools/release.ps1 start --version <版本> --distribution compat
```

每次手动 CI 仅构建所选变体，不跑应用或设备测试。发布工具核对请求身份、对应 profile、APK 版本/系统范围及 distribution.json。相同提交和版本的两个发布状态相互独立；状态查询有歧义时使用 status --version <版本> --run <runId>。

GitHub Release 标题统一使用 `v<完整版本号>`，例如 `v0.7.5.8_Compat`。主线正式版使用 GitHub Latest。兼容版以独立标签发布，更新范围与差分基准继续隔离。独立发布只更新对应 profile 和版本记录，不要求同时提升另一发行线版本。

五语言 README 为统一入口；兼容版说明与汇总在 docs/compatible/。历史 Compatible 构建和标签保留，只读校验允许旧分支身份；新发布不再接受该分支。无需在两分支间持续合并。

兼容版的 `versionName` 使用 `_Compat` 后缀，例如 `0.7.6_Compat`。编号测试版使用 `_beta1_Compat` 等后缀。版本比较以数值基础版本为准，APK 身份与安装校验使用完整版本。

v0.7.6 发布时，兼容客户端按 `compat_` 前缀识别发行线。它的公开标签保留为 `compat_v0.7.6_Compat`。更早没有 `_Compat` 后缀的标签保留 `compat_v` 前缀，以区分同号主线版。

后续带 _Compat 的版本统一使用 SA 前缀，例如 SA-0.7.5.4_Compat-20261119120000.apk；强制版使用 SA-ENF-0.7.5.4_Compat-20261119120000.apk。差分文件跟随目标 APK 文件名生成。已发布的 SACompat 附件及已冻结发布状态不改名。

### 兼容版 Git 标签过渡

自 v0.7.5.3 至 v0.7.5.10 的八个兼容版 Release 已迁到 `v<版本>_Compat` 标签。提交、Release ID 和附件保持原身份，新下载地址使用短标签。已分享的旧直链和保存了旧地址的下载任务需要改从对应 Release 重新下载。

新兼容版本使用 `config/distributions/compat.properties` 的 `tagPrefix=v`，正式版为 `v<版本>_Compat`，Beta 如 `v<版本>_beta1_Compat`。更新器同时读取短标签和历史前缀，按清单的发行身份、系统范围和附件身份决定是否可安装。主线 Latest 与 v0.7.6 兼容版下载入口各自独立。

v0.7.6 兼容版在原标签上提供同版本的较新签名构建。原版客户端可从应用内发现这个构建，安装后即可检查后续短标签更新。直接分享 APK 时使用原 Release 页面，替换后的附件使用新的构建时间文件名。
