# 开发检查与 GitHub 操作

## 本地检查

- 默认 tools/check-project.ps1：每次检查当前差异、Git索引中的凭据和私有数据；工具回归及必要编译按内容、环境和测试范围复用有效记录。应用或构建配置变化时编译debug；纯JVM测试变化运行JVM测试，androidTest/integration变化实际编译 `compileIntegrationAndroidTestKotlin`，混合文档/工具修改不掩盖测试编译。测试所需数据均为人工夹具，不读取手机、真实备份或真实Key。
- `-Plan` 只展示计划，不执行检查、不配置 SDK、不创建报告；`-BaseRef` 指定已知基线，默认 origin/main，引用缺失或非祖先会明确失败。`-Force` 只强制本次选定范围重新执行，不扩大范围。
- 已知相关用例通过 -TestClass 指定。只有这些用例通过时，不称为完整回归；不使用旧迁移输出冒充本轮校验。
- -Mode Full 显式运行全部JVM与SQLite校验；只有明确诊断需要时才加 -Release / -CompileAndroidTests。本地不默认R8打正式包。
- 缓存、临时文件和日志在项目内。凭据扫描读取索引，修改必须审阅、暂存后才能对实际提交版本检查；提交前hook再次扫描。
- Gradle输入按任务分开：生产源码/构建配置、JVM测试（含直接读取的README）、integration/androidTest各自进入相关摘要；README与JVM测试变化不使无关APK/生产编译失效。每个任务独立执行或复用，完整任务多次Gradle调用由同一daemon和Gradle增量缓存承接；这是为了让未变化任务保留独立有效证据。计划展示步骤、输入及复用限制，本身不声称命中缓存。执行开始和结束仍审计全部Git可见源码漂移。
- 本地证据在 `.local-notes/verification/`：包含输入/环境摘要、测试范围和日志校验。新失败或中断阻断旧成功；日志损坏不复用；编译class目录、测试XML、APK和SQLite生成SQL/Room输出必须存在且摘要匹配，清理/改写输出会重跑生产步骤；测试失败/中断仍阻断同输入下较早的完整测试成功。签名、上传、下载归档不要求重新测试。
- Android 本地入口 `tools/run-android-tests.ps1 -Serial emulator-N -TestClass 包名.类名[#方法]`。`-Plan` 只列出所选用例；`-UseExistingApks` 要求现成 APK、源码与构建环境匹配，否则拒绝。默认构建只在没有匹配 APK 时发生。两个入口共用结果解析，逐项核对用例身份与数量，未知选择、零测试、失败及未获解释的跳过均不放行。

## 唯一CI工作流

- Develop push / main PR 自动 mode=check：全量JVM、工具回归、SQLite校验；不编译设备测试包或正式包。
- 手动 mode=device/release：共用 `tools/verification.py` policy3，唯一平台声明由实际Gradle minSdk生成。旧min28保留API35/36；当前min36以API36为常规门禁，首次最低版本迁移运行API36全套；API37在具体兼容变化或专项要求时补测，未来min37则要求API37。无基线、未知或共享运行时/构建输入变化回退声明平台全套；通知专项覆盖声明平台，拍题专项取声明中的最高API，经审查独立测试类覆盖声明平台。通知组显式包含下载仓库、通知服务、独立更新页的恢复/已安装旧记录/未来旧记录，以及UiSmoke通知入口；共享FakeTransfers变化覆盖所有消费者。文档、纯JVM测试及普通工具变化不要求设备测试，runner/策略变化仍回退全套。
- 平台声明区分运行API37与SDK镜像标识37.0，CI明确要求 `system-images;android-37.0;google_apis;x86_64`（API35/36沿用整数标识），分配4096MiB内存；先检查准确镜像存在，缺失失败，不降级。设备安装前从两个实际APK读取minSdk并拒绝低于要求的模拟器。门禁验证平台/ABI、源码、run/attempt、候选与测试APK、逐项测试及计划重算；policy1历史证据仍按35/36解释，policy2的min36证据仍按36/37解释，未知policy拒绝。
- 正式 mode=release 仍执行一次全量JVM、SQLite及工具检查，加一次R8候选编译和计划要求的设备测试。`base_sha` 由release.start解析上一已发布构件来源：精确Release APK/兼容清单摘要和大小、签名包payload/构建时间与CI候选、成功main发布CI及其验证证据必须同时匹配。旧同版本替换可从匹配releaseId/版本/签名APK摘要的本机已归档状态找source/run，但仍重新验证全部公开构件和CI证据，不相信说明正文或target_commitish。证据过期或缺失保守回退不可变tag（覆盖较大差异）；tag不是祖先则留空走全套。纯版本字段变化可排除出设备风险，其他构建脚本变化不排除。CI不读取本机凭据或签名Secrets。
- main push / tag不重复触发CI。正式候选只允许main，可提供expected_sha防止触发时源码漂移。发布准备提交使用[skip ci]避免Develop重复检查。
- 候选包附件仍仅允许*-candidate.apk与release-manifest.json；schema2 增加计划摘要。设备完成后单独生成 `verification-evidence`，绑定run/attempt、源码、计划、候选及两个测试APK摘要、API和逐项测试结果；候选生成时不能提前声明设备通过。不含本机路径或凭据。
- 集成测试APK按计划需要生成一次，多个模拟器下载复用，附带源码与构件记录。设备测试依据普通JUnit4测试源码清单核对框架发现和实际执行身份，不能通过降低预期数量放行。
- JVM小型XML报告与人工数据设备日志保留7天；候选、测试APK及验证证据保留30天，过期缺失时明确阻止续跑。缓存来自CI自己生成的Gradle数据；绝不上传本机缓存或整个工作区。
- Windows工具检查并行，正式候选不被普通提交取消。不强制clean、不重复测试同一个未变化的构件。
- 设备镜像/网络故障只重试失败任务并复用已成功的 build 和候选；汇总门禁重跑后再次核对全部所需结果。若 build 自身失败，重跑可能产生新候选，旧候选的签名/摘要不能沿用。后续失败不能被早期成功覆盖，不无限自动重试。
- 当前不跨workflow自动寻找或拼接历史结果，也不把本地测试记录上传作为CI放行凭据；Develop push 与PR仍保留独立check。发布准备使用[skip ci]避开重复。后续若增加跨run复用，必须先实现可信来源、有效输入与失败历史的完整核验。

更新兼容信息继续使用 schema1：正式附件保留 `update-manifest.json`，新发布同时将相同元数据写入 Release 正文的隐藏 `studyassistant-update-manifest` HTML 注释。新客户端优先验证正文中的版本、最低 API、versionCode、APK 文件名/大小/SHA；旧发布仍读取独立附件，浏览器下载入口失败后只补试一次官方 GitHub asset API（无令牌），每个清单请求限制 15 秒和 64 KiB。元数据缺失或不匹配不按版本号猜测，不绕过系统要求；实际 APK 安装前继续校验 minSdk、版本、摘要和原签名。正文注释不出现在应用更新说明中。既有发布状态按原说明摘要续跑，不强制改写历史发布正文；原 JSON 字段及附加 provenance 仍兼容。

## 本地签名和可续跑发布

使用tools/release.ps1；它只设置当前进程JDK/SDK/项目内缓存。命令由协作开发者在用户授权的版本范围内执行：

- start --version VERSION：要求版本已准备、工作区干净、发布提交含[skip ci]；本地基础入口自动复用有效步骤，不能再手工先跑一遍整套；快进main与Develop，派发一次main release CI。存在标签时拒绝自动替换。
- status --version VERSION：只查询已记录的CI。请求超时也先查询匹配提交的run，不重复派发。
- verify --run RUN_ID：只接受成功的main release CI。schema2 必须通过 Verification gate，重新计算源码计划、核对基线与逐项证据；schema1 历史记录仍要求API35/36全套。下载候选白名单，校验源码/摘要，在本地使用原私钥和lineage签名，再验证证书及代码资源逐项摘要未变。重复调用复用已签名文件，不重编译、不重签。
- publish --run RUN_ID：从本机版本说明只取“更新内容”，再次扫描该节凭据；建立新标签、草稿，自 v0.6.7.9 起仅允许最终 APK 与 `update-manifest.json` 两个附件。清单 schema=1，包含不带 v 的 version、versionCode、minSdk、apkName、apkSha256、apkSize；版本和最低系统版本从最终签名 APK 提取并逐字段匹配 CI 清单，SHA-256 与大小绑定最终签名文件，不解析更新说明。后续schema2候选的发布清单还附加可向后兼容的 `provenance.sourceCommit/runId`，客户端仍按schema1兼容字段读取；旧清单/历史归档不改写。两附件分别核对服务端摘要/大小后发布，再分别下载核对并归档（清单归档名带 APK 版本/构建时间）。恢复时只上传缺失附件，错误已有附件拒绝覆盖；旧版本保留原单 APK 布局，不补写历史 Release。普通 publish 对已有版本/异源标签拒绝覆盖。 后续提高最低系统版本只修改 Gradle 的 minSdk（Android 16 为 36），不手工填写更新清单；CI 候选清单和本地发布清单均从 APK 读取。客户端从 v0.6.7.9 开始要求新版兼容清单，缺失或不匹配时禁止下载；仅已发布的 v0.6.7.8 及以前允许沿用 API 28 的旧约定。

同版本替换先用显式 `tools/release.ps1 start --version <版本> --replace` 准备并调度同一套完整门禁；已有成功候选也可直接验证。发布替换只允许显式 `tools/release.ps1 replace --run <成功release CI run>`（可选 `--notes <本地说明>`），普通 `start`/`publish` 不自动转换为替换。替换源码必须继承原不可变标签；先验证原已发布 APK 与成功 CI 来源，原标签不移动、不重新建立。新 APK 上传并下载核对后，才切换固定名 `update-manifest.json`；新清单下载核对后才删除旧 APK。两附件上传、清单删除/上传间隙、旧包清理、正文更新或归档中断，均查询现状后用同一 `replace --run` 续跑；已有未知附件、摘要冲突或无所有权记录的缺失会阻断。固定清单切换存在短暂缺失窗口，客户端按既有规则停止下载，不退回猜测兼容性。

状态以 `version + commit + runId + buildUtc` 绑定实际构建，常规发布与显式替换共用身份规则，不靠操作名选择签名缓存；不同构建各有独立状态。CI 调度前保存独立 pending 状态，旧 `<version>.json` 只读兼容，迁入新状态时不覆盖原文件。候选 artifact/清单身份变化拒绝复用签名；重新签名不改变 lineage 的 API28 rotation 阈值。`status --version <版本> --run <run>` 可明确选择构建，多义状态不猜测。

状态与私有操作日志存 .local-notes/release-state/，故障后续跑同一个run。编译输入、代码资源摘要和候选摘要必须匹配；签名后的APK摘要与候选摘要不同是正常现象。签名只允许改变签名信息，代码、资源和构建时间不变。

integration 是独立调试变体，release经过R8；同源码设备验证不等于正式APK或用户手机验收。用户反馈通知卡1%问题基本解决，本轮不以此为阻断项；工具测试不等同于手机验收，复发时保留现场证据。

CI从不使用用户或开发者真实DeepSeek Key，不上传题库、原图、备份JSON、恢复副本、崩溃日志、内部笔记或签名材料。Git/gh必要鉴权按用户授权使用已有凭据，不将凭据作为文件上传。模式扫描、文件名与格式拦截、上传白名单和人工差异审阅共同约束上传范围。

旧功能分支保持原样；若将来推送旧分支，其自身历史工作流仍可能执行，另行处理。

## Git 与 GitHub API 的选择

| 操作 | 默认方式 | 原因 |
| --- | --- | --- |
| status、diff、commit、merge、worktree、冲突处理、本地历史 | 本地 Git | 无网络，保留完整版本控制语义 |
| clone、fetch、上传新提交及批量文件 | Git | 批量传输对象；避免逐文件 API 创建零散提交 |
| CI 状态/日志、PR、Release、附件、远端 SHA 查询 | GitHub 连接器；能力不足时 gh/API | 直接查询服务状态，不为看状态执行 fetch |
| main 快进到已在 GitHub 上的 Develop 提交 | Git push；传输异常时 refs API | 已有对象无需再上传；更新前核对最新远端和祖先关系，force=false，写后复核 |
| 新 tag | Git；仅当对象已存在远端时可用 Git database API | 注解 tag 必须保留注解对象和目标提交，已有 tag 绝不移动 |

传输失败处理：

1. 本地 Git 命令不因网络故障而改用 API。
2. push/fetch 重置后先查询远端实际 SHA。连接失败不代表服务器没收下提交；已成功就不重推。
3. 需要 Git 传输时，最多额外尝试一次进程级 `git -c http.version=HTTP/1.1 ...`；不改全局代理、不关闭 TLS、不反复盲重试。
4. 目标提交已在远端时可安全走 refs API 快进；新提交仍未上传时，refs API 无法替代对象传输，停止并报告明确错误。非快进、权限、分支保护错误不能用 force 绕过。
5. API/gh 也可能遇到重置、限流和权限问题；没有 API 永远更快或 Git 永远更慢的保证。合并查询、按 SHA 核验结果，CI 通常间隔 20–30 秒查询一次。
6. refs API 触发 CI 与鉴权身份有关；Actions 自带 GITHUB_TOKEN 的 push 通常不会再触发新工作流。每次更新后确认对应 SHA 的 CI 实际出现，不凭 HTTP 成功推断。

官方依据：[Gradle 缓存](https://github.com/gradle/actions/blob/v4/docs/setup-gradle.md)、[Git HTTP 配置](https://git-scm.com/docs/git-config)、[refs API](https://docs.github.com/en/rest/git/refs)、[工作流触发](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)。


## 隔离测试的覆盖范围

- `integration` 包名为 `com.zsz.studyassistant.integration`，使用专门的 Application；不读取正式应用的 Key、崩溃日志或后台任务状态；备份调度测试只使用隔离包的私有状态和人工夹具。Manifest 移除联网权限以及正式服务/Provider；测试不应请求在线 AI、读取真实数据或使用签名材料。
- 保留真实 MainActivity 供 `UiSmokeTest` 检查设置返回路径、语言切换后的统计标签，以及 WebView 的实际列表编号。使用 ActivityScenario 和现有 Android 测试依赖，未增加 Compose 测试框架。
- `UpdatePageIntegrationTest` 用integration专属空Activity直接挂载注入FakeTransfers的UpdatePage，避开Home/API Key弹窗和系统下载；真实通知导航仍由UiSmoke覆盖。
- `ReliabilityRegressionTest` 只用人工数据检查 ViewModel 的草稿和复习生命周期；故障注入明确模拟下一题读取异常。`LargeFieldIntegrationTest` 检查大字段、原图、全文搜索及导出读取契约。
- 压缩备份测试覆盖 ZIP 分卷完整性、SAF 所有权与读回校验、空错题本不调度、前台成功事件、后台终局失败通知、v3 兼容导入和 96 MiB 人工题库完整往返。系统通知授权只针对 integration 包，不触及正式应用或真实手机。
- 本地仍通过 `tools/run-android-tests.ps1 -Serial emulator-<端口>` 明确选择模拟器；CI 沿用同一套测试的自动发现，不另起重复工作流。测试包可打开主界面不等于已在用户手机实测，不能据此承诺所有机型效果。


## 正式与测试更新渠道

- `tools/release.ps1 start --version VERSION` 默认正式版；只有明确安排测试版时使用 `--channel beta`。两类都使用原有完整发布门禁和原证书，跨渠道共用严格递增的 versionCode、各版本使用独立标签；不移动已有标签，不将同一标签在两种构件间切换。
- 新状态绑定 channel 并传入构建状态；新发布清单可选 channel 与 GitHub prerelease 一致。测试版 `prerelease=true`、`make_latest=false`，不抢占正式版 latest。Release 最后一个可见条目同时说明最低 Android 版本和正式/测试属性；旧无 channel 状态按原清单与正文摘要续跑。
- 客户端默认同时查官方 Releases 列表，列表失败回退官方 latest。先读取兼容信息再确定最终渠道；仅附件清单声明 beta 也不能挤掉真正正式版。可用测试版在前并提示可选和不稳定，正式版说明及独立下载在后；不提供低于当前 versionCode 的安装包。下载校验与恢复流程不降低门禁，跳过测试版不隐藏正式版。
