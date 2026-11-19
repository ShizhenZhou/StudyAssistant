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

主线沿用 v… 标签和 Latest，兼容版沿用 compat_v… 标签且不设 Latest；更新范围与差分基准继续隔离。独立发布只更新对应 profile 和版本记录，不要求同时提升另一发行线版本。

五语言 README 为统一入口；兼容版说明与汇总在 docs/compatible/。历史 Compatible 构建和标签保留，只读校验允许旧分支身份；新发布不再接受该分支。无需在两分支间持续合并。

兼容版新构建版本号使用 _Compat 后缀，例如 0.7.5.2_Compat；Beta 为 0.7.6_beta1_Compat。已有发布和历史记录不改名。版本比较去掉发行后缀，但 APK 身份与安装校验使用完整版本。标签继续使用 compat_v 前缀以保持旧客户端发行隔离。

后续带 _Compat 的版本统一使用 SA 前缀，例如 SA-0.7.5.4_Compat-20261119120000.apk；强制版使用 SA-ENF-0.7.5.4_Compat-20261119120000.apk。差分文件跟随目标 APK 文件名生成。已发布的 SACompat 附件及已冻结发布状态不改名。
