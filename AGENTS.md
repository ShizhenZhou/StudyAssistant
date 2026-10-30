# StudyAssistant 开发约定

开发入口见 `README.md`；改数据层时读 `docs/DATA_SCHEMA.md`。本机环境笔记（如存在）位于 `.local-notes/environment/DEV_ENV_NOTES.md`。上一级 CodexWorkSpace/AGENTS.md 的通用约定同时适用。

## 当前流程

1. 检查分支和工作区，读取相关代码并说明发现；直接修改代码，不要求用户手工套补丁。Git 提交必须使用 Conventional Commits 类型前缀（feat: / fix: / ci: / build: / docs: / test: / refactor: / chore: 等），标题说明具体改动；合并提交也遵守。
2. 查看精确差异；本地默认只做静态检查与验证范围规划，测试由一次在线CI负责，不本地跑完再在线重跑。同一变更不先check/device CI再release CI；仅排查具体失败或用户要求时，本地定向复现，不机械全套。
3. 用中文说明结果、验证和限制。测试与手机实测分开；未实测的效果不声称已确认。
4. 日常在 Develop 或适当的 codex/ 分支开发。用户指定正式版本（非 Beta）后即持续授权发版：本地基础检查复用有效步骤后先合并 main，再运行一次 mode=release CI（全量JVM/工具/SQLite、按共用策略确定的设备测试、一次未签名候选 APK 与公开校验清单）。设备平台由实际minSdk统一声明：旧min28为API35/36，当前min36以API36为常规门禁，API37仅在具体兼容变化或专项要求时补测，首次迁移及未知影响回退声明平台全套；门禁必须核对计划及源码/构件/逐项证据，不得仅删除作业或降低测试数量放行。CI 成功后仅在本地使用原证书与 lineage 签名，核对代码资源与候选包一致，再建立新标签、发布与下载校验归档。main 推送和 tag 不重复构建，无需逐次再确认。用户要求先测试后合并时，使用 start --test-before-main：精确 Develop 源提交在同一次 release CI 内先完成测试及预门禁，promote --run 校验证据后快进 main；同 workflow 的候选作业验证晋级记录后再构建，最终门禁继续绑定全部测试和候选。不要额外派发一轮 check CI。Beta 按当次用户安排处理；普通未指定版本的开发任务不自动发版。已有标签不移动、不强推。
5. 本地默认 tools/check-project.ps1 的 Basic 模式，仅静态检查，不自动执行JVM/工具/设备测试或编译；-TestClass与Full仅供明确诊断。本地推荐范围不代表已执行，CI是默认唯一测试执行方。通用发版入口 tools/release.ps1（start/status/promote/verify/publish/replace），常规路径 start→status→publish，独立verify仅按需诊断，不先例行verify再publish；状态仅存 .local-notes/release-state/。使用标准 Git/Gradle；CI 与 Git/API 分工见 docs/development.md。网络失败先查远端结果，必要时仅重试一次进程级 HTTP/1.1；只对远端已有对象使用非强制 refs 更新，不重复盲重试。已退役脚本与组件仅在被忽略的 .local-notes/retired-files/ 本地归档，不再作为开发入口。
6. 只读/交接不启动测试。验证前可用 -Plan 查看范围；本地证据存 .local-notes/verification/，按任务有效输入/环境/日志/输出摘要复用，README/JVM/设备测试输入不使无关编译或APK失效；仍审计全源码漂移，失败/中断阻断旧成功。androidTest/integration变化交CI编译设备测试；普通check只编译不运行模拟器。设备入口明确 emulator 序列号和相关类/方法，本地全套须显式-All，空选择拒绝执行；CI环境故障仅处理失败任务，不从头重建已成功候选。发布基线须绑定精确已发布APK与成功CI来源；旧替换状态仅作可复核线索，证据缺失回退不可变tag或全套。当前不跨workflow拼接测试结果、不上传本机证据；有远端ID/大小/SHA绑定的真实下载字节可本地缓存，但每阶段仍查询远端状态/身份并重验，最后公开附件必须真实下载；签名/发布/归档不触发应用测试。流程变化只补在线工具回归；文案/样式/纯计算优先编译或JVM，不为每项新增补模拟器用例。系统权限、后台、存储、导航等边界变化才选择设备；策略以新版计划和逐项证据核验，历史策略保持兼容。

## 必须保持的行为

- 这是单人自用工具。业务数据库和备份在本机；AI 请求发送必要题干、图片、上下文或用户主动分析的标签到 DeepSeek 官方 API。Key 用于鉴权，不得泄露；更新检查只访问 GitHub Releases。
- 不清数据、不卸载、不删除备份。数据变更需要兼容迁移、读端兜底、迁移前备份并更新 DATA_SCHEMA；当前主库 DB v10、备份 v4（兼容导入 v3 JSON），撤销辅助库独立。
- 界面文案维护简体、英文、日文、韩文；繁体 s2t 自动转换。README 默认简体中文，顶部统一链接 docs/readme/README.zh-TW.md / docs/readme/README.en.md / docs/readme/README.ja.md / docs/readme/README.ko.md；各页为完整对应语言内容，不在每个版本下重复语言折叠。发版记录维护 README 五语言页面与 Changelog 四语言（繁体沿用 s2t），版本块之间留空行；README 与应用版本记录在 v0.6.8 / v0.6.7.9 之间统一标注“以上版本仅支持Android 16+”并加分割线，不在各版本条目重复最低要求；GitHub Release 更新内容最后一条同时说明最低支持 Android 版本和正式版/测试版属性；测试版显式使用 GitHub prerelease、不得设为 latest；更新默认同时检查两个渠道，测试版可选并提示不稳定，正式版另列且两者独立校验；DB / Bak 版本仅在格式版本实际变化时写入更新内容，未变化不重复列出。
- 导航统一走 ui/AppNav.kt；公式 HTML 裁剪需保留上下 padding，KaTeX display 不重复套层。
- 永久题目编号在错题、首次搜题记录及备份之间保持一致；从错题本打开不新增搜题记录，记录按最早时间排序。
- 合并标签必须先让用户预览确认，事务内重新校验；保留最近一次合并的独立持久撤销。
- 本机 API Key、GitHub 凭据、签名私钥/口令、真实题库/图片/备份、崩溃日志和内部笔记不得成为 Git 提交、CI 输入、附件或本地缓存上传。CI 只用源码和人工夹具，不读取签名 Secrets；官方服务必要鉴权按原授权使用凭据，不记录值。
- 正式 APK 使用现有自有证书及 lineage；不得重新生成替代签名。发布后下载核对 SHA，并归档到 history_apk。发布说明取 `.local-notes/releases/RELEASE-v<版本>.md` 的“更新内容”。设计、排查、逐版本发布过程只在 `.local-notes/` 本地归档，不在仓库根目录新增此类 MD、不提交到 GitHub。必要的代码维护/测试说明可以保留在 docs/。

`.local-notes/legacy-deepseek/` 是本机历史档案，其中的旧版本、旧路径、只交补丁或禁止构建等陈述不代表当前工作流程。仅在追溯特定历史问题时读取。
