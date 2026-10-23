# 标签分析在思考模式下立即报错：排查与补丁

## 结论

v0.6.6.5 的标签分析显式构造 `ThinkingOption("enabled")`；该值等于 DTO 的默认值，生产 JSON 配置会把它编码为 `"thinking": {}`。2026-10-23 用虚构内容向 DeepSeek 官方接口对照请求，空对象立即返回 HTTP 422；省略字段或显式 disabled 均返回 HTTP 200。与用户报告的“思考模式点击分析立刻报错，普通拍题正常”一致。

## 根因与依据

- `data/ApiModels.kt`：`data class ThinkingOption(val type: String = "enabled")`。
- `data/ApiService.kt`：`Json { ignoreUnknownKeys = true }` 未开启 encodeDefaults，省略等于默认值的字段。
- 已发布标签 v0.6.6.5 的 `TagMergeViewModel.analyze(language: String)` 显式构造 enabled；普通解题 `StudyAssistant.chatStream(...)` 已采用 `thinking = if (!thinkingEnabled) ThinkingOption("disabled") else null`，开启时省略整个字段。
- 三次最小对照使用同一个本地测试 Key、同样的虚构 system/user 消息、相同模型及 max_tokens=16；只比较 thinking 字段。没有发送真实标签、题目、图片或对话，不记录凭据或响应原文。

| thinking 参数 | 实际 HTTP 状态 |
|---|---|
| `{}` | 422，invalid_request_error |
| 不传 | 200 |
| `{"type":"disabled"}` | 200 |

## 补丁锚点与顺序

1. `data/TagMergeSuggestions.kt`，锚点 `fun request(base: TagInventory, language: String, thinkingEnabled: Boolean)`：统一构造标签分析请求；思考开启时不传 thinking，快速模式明确传 disabled。只改标签分析，不调整全局 JSON 默认值。
2. `TagMergeViewModel.kt`，锚点 `fun analyze(language: String)`：调用上述请求构造函数，保留原模型、输出上限、分析范围、取消与预览机制。
3. `data/TagAnalysisError.kt`，锚点 `fun from(error: Exception, httpCode: Int? = null)`；`ui/TagMergePage.kt`，锚点 `model.errorKey?.let`：参数、授权、余额、限流、服务端、网络、超时及格式错误分别提示，HTTP 错误显示数字状态码。异常消息、错误响应正文与请求正文不转发到界面或日志。
4. `ui/L10nTables.kt`：新增上述提示的简体、英文、日文、韩文；繁体继续自动转换。
5. `TagMergeSuggestionsTest.kt`：新增实际序列化请求、快速模式、HTTP 分类与无敏感原文输出的回归测试。

## 验证与范围

- 对照请求已完成，结果见上表；本地 Kotlin / 测试编译、196 项单元测试（21 个测试类）、正式变体构建（含 R8 / vital lint）、SQLite 迁移 / 主库结构检查均通过。补测候选 APK 的自有证书与 v3 + lineage 签名校验通过。CI 状态以本次 Develop 提交实际运行结果为准。
- 不改版本号或 versionCode，主库 DB v10、备份 v3 与独立撤销库格式均不变。
- 2026-10-24 用户明确授权同版本替换发布：在当前 v0.6.6.5 的 README 与四语言 Changelog 补充本次附件修正说明，不改更早版本条目；保留原标签，版本号与 versionCode 不变。
- 不操作手机数据库或备份，不自动安装。

## 真机补测

使用补丁候选包，在当前 264 个标签、思考模式下点击 AI 分析，确认不再立即因 HTTP 422 失败，观察是否得到可编辑建议；再确认快速模式仍能分析。该补测不需要执行实际标签合并。完整标签分析的耗时、建议质量和手机界面仍须补测，不能用三次极小参数请求替代。
