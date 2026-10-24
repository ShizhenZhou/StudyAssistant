# 开发检查与 GitHub 操作

## 本地检查

- 默认 tools/check-project.ps1：检查差异、Git索引中的凭据和私有数据、工具回归；应用或构建配置变化时编译debug。测试所需数据均为人工夹具，不读取手机、真实备份或真实Key。
- 已知相关用例通过 -TestClass 指定。只有这些用例通过时，不称为完整回归；不使用旧迁移输出冒充本轮校验。
- -Mode Full 显式运行全部JVM与SQLite校验；只有明确诊断需要时才加 -Release / -CompileAndroidTests。本地不默认R8打正式包。
- 缓存、临时文件和日志在项目内。凭据扫描读取索引，修改必须审阅、暂存后才能对实际提交版本检查；提交前hook再次扫描。

## 唯一CI工作流

- Develop push / main PR 自动 mode=check：全量JVM、工具回归、SQLite校验；不编译设备测试包或正式包。
- 手动 mode=device：上述检查加独立测试包和Android35模拟器运行。
- 手动 mode=release：上述完整检查与模拟器，加一次R8 release编译；产物是未签名候选APK及公开清单，不读取本机凭据或GitHub签名Secrets。
- main push / tag不重复触发CI。正式候选只允许main，可提供expected_sha防止触发时源码漂移。发布准备提交使用[skip ci]避免Develop重复检查。
- 候选包附件仅允许*-candidate.apk与release-manifest.json；清单含提交、版本、UTC时间、摘要、代码资源摘要、公开证书指纹与测试数量，不含本机路径或凭据。
- 集成测试APK只在device/release上传，复用构建产物不重复编译。测试数量从普通JUnit4测试源生成，与框架发现及执行数量核对；零测试/失败/数量不符均失败。
- JVM小型XML报告与人工数据设备日志保留7天，候选包保留30天。缓存来自CI自己生成的Gradle数据；绝不上传本机缓存或整个工作区。
- Windows工具检查并行，正式候选不被普通提交取消。不强制clean、不重复测试同一个未变化的构件。

## 本地签名和可续跑发布

使用tools/release.ps1；它只设置当前进程JDK/SDK/项目内缓存。命令由协作开发者在用户授权的版本范围内执行：

- start --version VERSION：要求版本已准备、工作区干净、发布提交含[skip ci]；执行本地基础检查，快进main与Develop，派发一次main release CI。存在标签时拒绝自动替换。
- status --version VERSION：只查询已记录的CI。请求超时也先查询匹配提交的run，不重复派发。
- verify --run RUN_ID：只接受成功的main完整release CI；下载候选白名单，校验源码/摘要，在本地使用原私钥和lineage签名，再验证证书及代码资源逐项摘要未变。重复调用复用已签名文件，不重编译、不重签。
- publish --run RUN_ID：从本机版本说明只取“更新内容”，再次扫描该节凭据；建立新标签、草稿及上传一个最终APK，核对摘要后发布，再下载核对并归档。已有版本/异源标签拒绝覆盖。

状态与私有操作日志存 .local-notes/release-state/，故障后续跑同一个run。编译输入、代码资源摘要和候选摘要必须匹配；签名后的APK摘要与候选摘要不同是正常现象。签名只允许改变签名信息，代码、资源和构建时间不变。

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
