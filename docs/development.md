# 开发检查与 GitHub 操作

## 检查入口

- 本地：`tools/check-project.ps1` 检查差异、暂存版本的密钥、工具回归、全部 JVM 单测及隔离 SQLite 迁移。修改后应先审阅并暂存，再运行凭据检查；扫描器读取 Git 索引，不读取未暂存内容。
- `-CompileAndroidTests` 额外编译独立集成测试包；`-Release` 额外构建 release。两个开关可同时使用，所有 Gradle 任务合并为一次调用。
- 排查某个用例时，可先运行 `./gradlew.bat testDebugUnitTest --tests "完整测试类名"`；该结果只代表选中的测试，合并/发版前仍需完整检查。
- 缓存命中可以复用结果，不强制 clean 或 rerun-tasks。测试直接读取的 README、构建脚本与公式资源显式声明为输入；迁移校验所需文件显式声明为输出。
- JVM 单测禁止 Android 桩方法静默返回默认值；意外框架调用直接失败，文件、Room 和 SAF 场景由隔离 Android 集成测试实际验证。

## CI

只有 `.github/workflows/build.yml` 一份工作流：

- main / Develop push、main PR、手动触发：完整 JVM/工具检查、集成测试包编译和 SQLite 校验。
- v* tag：上述检查加 release/R8/vital lint、原证书及 lineage 重签、上传 APK 和创建草稿。整个工作流通过后按维护者授权发布，不自动移动旧标签。
- Ubuntu 构建任务将单测及所有变体编译合并为一次 Gradle 调用；轻量 Windows 工具回归并行运行，不重复 Android 构建。Linux 工具测试会跳过仅限 Windows 的 4 项检查。
- main / Develop 的可信 push 可以保存 Gradle 缓存，PR / tag 只读。默认分支缓存可供其他 ref 恢复；Develop 缓存主要供 Develop 后续构建使用，不能保证 main 直接命中它。
- 同一分支的过期 run 可以取消；不同分支与 tag 不互相取消，tag 不中途取消。构建超时 20 分钟，Windows 工具检查 8 分钟；失败保留 JVM 报告 7 天。
- 保留 main、Develop 与 tag 各自的完整检查；本轮不做跨分支跳测。PR 的合并测试提交也不等同于分支原提交。
- v* 标签或手动输入 android_tests=true 时，在独立 Android 35 模拟器实际执行 16 项集成测试。复用 build 任务产出的两个 APK，不重复编译；检查设备、包名、instrumentation target 和通过数量。普通分支仅编译测试包。本机入口 tools/run-android-tests.ps1 只接受模拟器，禁止用户手机作为夹具。

当前配置不再监听 multi_questions。旧分支按用户要求保持原样：若以后向它推送代码，GitHub 使用该分支自身旧工作流，它仍可能触发旧 CI；需要改旧分支时再单独处理。

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
