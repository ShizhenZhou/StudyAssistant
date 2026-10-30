# StudyAssistant 开发约定

开发入口见 `README.md`；改数据层时读 `docs/DATA_SCHEMA.md`。本机环境笔记（如存在）位于 `.local-notes/environment/DEV_ENV_NOTES.md`。上一级 CodexWorkSpace/AGENTS.md 的通用约定同时适用。

## 当前流程

1. 日常在 Develop 或 codex/ 功能分支开发；先看工作区/相关源码与差异。提交及合并标题使用 Conventional Commits（feat/fix/build/ci/docs/refactor 等），说明具体变化。
2. 本地最小必要验证，不重复执行。默认 tools/check-project.ps1 仅差异/敏感内容检查；确有必要才显式 -Compile 或 -TestClass 类/方法。同样输入已验证则复用；改动只补受影响部分。不默认全JVM、SQLite、工具套件或模拟器，不因未知路径、失败或中断回退全套。
3. 功能开发不得自行新增测试流程、必跑门禁或扩大测试范围。新增测试应有具体风险和必要性，优先复用已有用例；调整流程需说明收益和成本。不得用修测试/反复重跑替代应用开发，也不把测试失败未经证实归因于环境。
4. 相机、通知、动画、振动、后台等真实效果优先提出真机测试：给用户可安装Beta、简短操作步骤和预期结果；必要时有线/无线ADB辅助。先确认目标序列号与用户允许的操作；不自动选手机，不清数据/卸载/覆盖用户备份。日志最小化且只本地保存。Beta后改动只补相关验证；未实测不得声称确认。
5. 完成最小验证/必要真机反馈后，规范合并main（可快进保留规范提交，或 feat/fix/build: xxx 的合并提交），确认最终源码；随后一次手动CI构建，再本地原证书与lineage签名、发布、真实下载SHA核验归档。CI只构建未签名APK并检查源码/敏感内容/包身份，不默认任何应用、工具或设备测试；push/PR/tag不另起CI。只读/交接不启动测试。普通未指定版本任务不自动发版；正式版本持续授权规则保留，Beta按当次安排，不移动旧标签。
6. 发布入口 tools/release.ps1 start/status/verify/publish/replace；start要求已合并并推送的干净main，不自行合并、不测试。常规start→status→publish，verify按需，不重复跑。新schema3清单只证明构建身份，不伪称测试成功；旧schema1/2只为历史发布读取兼容，不套用到新发布。取消check/device在线模式、pre-main gate、promote、全量回退及历史测试基线作为新版本前置条件。失败只定位相关步骤，网络结果不明先查远端，必要时只重试一次；不反复派发。
7. 数据安全、更新兼容、源码与APK一致、原签名和下载完整性仍须认真核对。测试是可选工具，不是用例数量目标。旧脚本/工具用例归档在被忽略的.local-notes/retired-files；当前流程以本文件及docs/development.md为准，旧版本记录不作为执行指令。

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
