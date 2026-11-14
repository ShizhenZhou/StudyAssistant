# 文件提问与 OCR 接口

图文提问和追问的“更多功能”提供联网、文件、导出全部与公式键盘入口；选择文件后显示于输入框上方，发送时才上传。框选页的“OCR 校对”只在用户明确点击开始识别后调用，不作为每张图片的必经步骤。

## 文件提问

`AiFiles.session()` 捕获当前凭据，返回 `AiFileSession`。通过 `uploadDocument(File, purpose, lifetimeSeconds)` 显式上传，默认使用 `TEXT_AND_FIGURES` 和 24 小时有效期。支持 PDF、Word、PowerPoint 与常见图片；单文件最多 100 MiB，采用流式上传，不整份读进内存。服务端最多处理 1000 页，客户端不伪称已预先检查页数。

上传返回 `RemoteAiFile`，绑定账号摘要和文件元信息。`StudyAssistant.chatStream(..., documents = references)` 可在发送时向最后一条 user 消息附加引用，检查账号、过期时间和最多 20 个引用的限制。原始 messages 不被修改。历史会话中原有的 file 引用也计入数量。

`listDocuments` 提供分页查询；`retrieve` 获取指定文件；`delete` 只删除当前账号下明确指定的远端引用。请求取消或连接中断后，服务端可能仍完成解析并计费，客户端不会自动重传、猜测文件名匹配或删除远端文件。调用方应查询并确认结果，再决定是否重新上传。

## 按需 OCR

`session.recognize(File, purpose)` 只请求 OCR，返回 `OcrResult`。保留逐页原文及坐标标记，`complete` / `OcrPage.complete` 明确标记截断；未知终态、重复或缺失页码、HTTP 200 内错误对象均拒绝作为成功结果。结果读取有 32 MiB 本地上限，取消会关闭正在读取的响应。

`selectedPages(indices)` 仅筛选已完成识别的返回页，不表示仅识别/计费这些页。OCR 用量独立于聊天用量；服务端按页计费，返回 token 数仅供参考。OCR 原件不会存入 Files API，结果也不能当作 file_id 使用。

## 页面与数据边界

- 文件卡片提示上传解析可能按页计费；用户发送才调用接口。支持 PDF / Word / PPT，单份上限 100 MiB，一轮上下文最多 20 个文件引用。原件流式导入私有 SHA 存储，历史重开和备份恢复不依赖临时远端 ID。
- 当前提交完整文档，未提供选页；官方接口没有选页参数，若需只传部分页，应先生成对应的本地文档。远端引用只缓存于进程内；到期或重开进程后再次发送可能重新解析。上传结果不明时至少等待 5 分钟再显式重试，避免立即重复计费。
- OCR 内容先交给用户校对，不自动覆盖题干或提交问答。截断内容须明确处理后再发送。
- ChatItem 文档元数据与私有草稿兼容缺省读取；Bak v4 的通用资源承载原件，恢复前验证 SHA 和大小。主库结构版本不变。移除输入框中的附件不删除已保存的原件；未引用原件的保守回收仍为后续优化。
- OCR 展示当前框选原图、可编辑文本及公式预览；截断结果须人工核对确认。附图按 URI 绑定校对文字，删除附件同时删除校对，多图发送时按最终顺序编号。
- 会话和异常不可记录凭据或原始响应；错误对外只暴露固定说明、HTTP 状态及允许列表中的参数名。

官方协议：[Files API](https://api-docs.deepseek.com/guides/files_api/)、[文档解析](https://api-docs.deepseek.com/guides/document_parsing/)、[OCR](https://api-docs.deepseek.com/guides/ocr/)。
