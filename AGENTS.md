# StudyAssistant 开发约定

开发入口见 `README.md`；改数据层时读 `DATA_SCHEMA.md`。本机环境笔记（如存在）位于 `.local-notes/environment/DEV_ENV_NOTES.md`。上一级 CodexWorkSpace/AGENTS.md 的通用约定同时适用。

## 当前流程

1. 检查分支和工作区，读取相关代码并说明发现；直接修改代码，不要求用户手工套补丁。
2. 查看精确差异，运行与改动相关的编译、单测和必要检查。普通低风险修改无需机械地每一两处就编译；较大改动拆分验证。
3. 用中文说明结果、验证和限制。测试与手机实测分开；未实测的效果不声称已确认。
4. 日常在 Develop 或适当的 codex/ 分支开发。用户指定正式版本（非 Beta）后即持续授权发版：本地基础检查后先合并 main，再运行一次 mode=release 的完整 CI（测试、模拟器、未签名候选 APK 与公开校验清单）。CI 成功后仅在本地使用原证书与 lineage 签名，核对代码资源与候选包一致，再建立新标签、发布与下载校验归档。main 推送和 tag 不重复构建，无需逐次再确认。Beta 按当次用户安排处理；普通未指定版本的开发任务不自动发版。已有标签不移动、不强推。
5. 本地默认 tools/check-project.ps1 的 Basic 模式，相关用例用 -TestClass；Full 仅供明确排查。通用发版入口 tools/release.ps1（start/status/verify/publish），状态仅存 .local-notes/release-state/。使用标准 Git/Gradle；CI 与 Git/API 分工见 docs/development.md。网络失败先查远端结果，必要时仅重试一次进程级 HTTP/1.1；只对远端已有对象使用非强制 refs 更新，不重复盲重试。已退役脚本与组件仅在被忽略的 .local-notes/retired-files/ 本地归档，不再作为开发入口。

## 必须保持的行为

- 这是单人自用工具。业务数据库和备份在本机；AI 请求发送必要题干、图片、上下文或用户主动分析的标签到 DeepSeek 官方 API。Key 用于鉴权，不得泄露；更新检查只访问 GitHub Releases。
- 不清数据、不卸载、不删除备份。数据变更需要兼容迁移、读端兜底、迁移前备份并更新 DATA_SCHEMA；当前主库 DB v10、备份 v4（兼容导入 v3 JSON），撤销辅助库独立。
- 界面文案维护简体、英文、日文、韩文；繁体 s2t 自动转换。发版记录维护 README 与 Changelog 四语言，版本块之间留空行；DB / Bak 版本仅在格式版本实际变化时写入更新内容，未变化不重复列出。
- 导航统一走 ui/AppNav.kt；公式 HTML 裁剪需保留上下 padding，KaTeX display 不重复套层。
- 永久题目编号在错题、首次搜题记录及备份之间保持一致；从错题本打开不新增搜题记录，记录按最早时间排序。
- 合并标签必须先让用户预览确认，事务内重新校验；保留最近一次合并的独立持久撤销。
- 本机 API Key、GitHub 凭据、签名私钥/口令、真实题库/图片/备份、崩溃日志和内部笔记不得成为 Git 提交、CI 输入、附件或本地缓存上传。CI 只用源码和人工夹具，不读取签名 Secrets；官方服务必要鉴权按原授权使用凭据，不记录值。
- 正式 APK 使用现有自有证书及 lineage；不得重新生成替代签名。发布后下载核对 SHA，并归档到 history_apk。发布说明取 `.local-notes/releases/RELEASE-v<版本>.md` 的“更新内容”。设计、排查、逐版本发布过程只在 `.local-notes/` 本地归档，不在仓库根目录新增此类 MD、不提交到 GitHub。必要的代码维护/测试说明可以保留在 docs/。

`.local-notes/legacy-deepseek/` 是本机历史档案，其中的旧版本、旧路径、只交补丁或禁止构建等陈述不代表当前工作流程。仅在追溯特定历史问题时读取。
