# 当前开发环境与流程

更新：2026-10-24。此文件取代旧工作区环境笔记作为当前入口；历史文档保留原样供查询。

## 当前状态

- 项目：StudyAssistant，Kotlin / Compose / Room，仓库 ShizhenZhou/StudyAssistant。
- 版本 0.6.6.5 / code 62，DB v10 / Bak v3，独立标签撤销辅助库。
- 迁移前 main / Develop 源提交：9cdcc1ed39e3a4b54017bea2a8ee85066e5c2f43。
- 最新正式附件：StudyAssistant-0.6.6.5-202610241034.apk，SHA-256 `150d33280573650658c1842b64c168ad48d205507625f0a0df7541c87a9b197e`。
- v0.6.6.5 标签保留原位置，附件来自后续修正提交；207 项单测和两分支 CI 在发布时通过，真实标签分析效果待用户补测。

## 日常统一检查

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/check-project.ps1
# 需要检查正式构建时：
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/check-project.ps1 -Release
```

入口统一检查工作区及暂存差异、已跟踪的暂存版本凭据、开发工具测试、标准 Gradle 单测和隔离 SQLite。默认不编译 release；`-Release` 增加正式构建。两者均不执行发布重签、不联网分析用户题目、不安装、不推送、不发布；Gradle 首次缺依赖时可能下载。输出在 app/build/checks/，失败返回非零退出码。

## 本机入口

工程实际位置为 `E:/UserData/shizhen/documents/CodexWorkSpace/StudyAssistant`，路径全为 ASCII。旧“文档”路径当前无效。

在项目根目录打开 PowerShell，执行（前面的点加空格表示将变量设置到当前终端）：

```powershell
. ./tools/codex-env.ps1
./gradlew.bat testDebugUnitTest --console=plain -Pkotlin.compiler.execution.strategy=in-process
./gradlew.bat assembleRelease --console=plain -Pkotlin.compiler.execution.strategy=in-process
python tools/verify-identity-migration.py
# Sign only when preparing or validating an authorized release:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/sign-release.ps1 -InApk app/build/outputs/apk/release/app-release.apk -OutDir app/build/identity-work/signed
```

环境入口只设置当前终端的 JAVA_HOME、PATH、ANDROID_HOME、GRADLE_USER_HOME、ANDROID_USER_HOME 和 TEMP/TMP，不改变系统环境变量。SDK / JDK 继续使用电脑上现有安装；缓存、临时文件与构建输出留在项目内。

- JDK：21，现有安装 `C:/Program Files/Java/jdk-21.0.10`。环境入口检查实际 release 文件中的主版本，优先使用有效 JAVA_HOME，再查找 Program Files/Java；可传 -JavaHome，不再写死补丁版本。
- SDK：`%LOCALAPPDATA%/Android/Sdk`，platform android-37.0、build-tools 36.0.0；是否升级以实际需求为准。
- Gradle：使用仓库 wrapper，不依赖全局安装。新英文路径优先使用标准单测命令；旧 tools/run-unit-tests.ps1 保留作有明确路径问题时的备选。
- 签名：secrets.properties 为本地忽略文件，已指向上一级 `.secrets/StudyAssistant/`。旧 debug 身份同时放入项目 `.gradle/android-home/`，不重新生成签名。
- Git：保留 `.githooks` 密钥扫描。常规 Git 优先，网络失败按错误调查；GitHub API 作为备选，禁止为省事关闭 TLS 校验。
- tools/git-sync.ps1、check-sandbox.ps1 保留但不是必经流程；不自动使用其旧代理或工作区假设。

## 目录和资料

- app/：应用源码、资源及单测；tools/：项目工具；history_apk/：正式附件归档。
- RELEASE-v*.md：相应版本发布说明；DESIGN-*.md / FIX-*.md：当前设计和调查依据。
- docs/legacy-deepseek/：从旧工作区复制的 20 份历史说明，保持原文；本机忽略，不作为当前指令，也不默认发布到公共仓库。
- app/build/identity-work/：本机报告和隔离验证产物；旧报告保留历史路径，不作为当前脚本直接重跑。
- 总工作区 migration-records/：本次迁移的文件校验与环境验证记录。

## 发版

用户明确要求发版后，按实际修改更新版本及文档，检查差异、单测、正式构建、签名和 CI，再更新 main / Release。用户要求同版本替换时不 bump、不移动标签，先校验新附件再移除旧附件。完成后重新下载校验并归档；手机安装需遵循当前用户授权。

本次仅迁移环境和开发约定，不修改应用行为、数据库、版本号或现有 Release。

## 本次迁移验证

标准 Gradle 单测实际执行：207 项、21 个测试类，失败 / 错误 / 跳过均为 0。新目录的 Git 远端读取、GitHub API、本地 Git 对象连通性检查通过；签名脚本使用迁移后的密钥完成隔离签名，证书与 v3 + lineage 校验通过。没有发布新附件、安装手机或修改系统配置。复制来的历史 PowerShell 脚本可能受来源标记限制，需要时仅对单次进程使用 ExecutionPolicy Bypass，不全局放开策略。

## 流程复查（2026-10-24）

Git 钩子与 CI 共享 tools/check-secrets.py；旧 PowerShell 扫描入口仅转发兼容。扫描读取 Git index，阻止已知私密文件名与凭据特征，Git 读取错误也失败。工具自身使用隔离 Git 夹具测试，不读取真实密钥。CI 仅 tag 读取签名材料，缺失材料 / 附件不再退回替代签名包；这些 CI 改动本地提交后仍需推送运行才算远端验证。旧目录与旧共享缓存保持不动。

本轮复查实测：统一入口完成正式构建、207 项应用单测及隔离 SQLite 校验；开发工具 12 项回归检查通过（其中 3 项仅适用于 Windows PowerShell，Linux CI 跳过这些平台专属检查）。已离线检查 5 个 CI shell 段，并模拟空签名 Secrets，退出码为 1 且未生成签名文件。未推送 CI 配置，尚无本轮远端工作流验证结果。
