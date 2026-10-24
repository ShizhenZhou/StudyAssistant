# 开发检查与 GitHub 操作

## 检查入口

- 本地：`tools/check-project.ps1` 检查差异、暂存版本的密钥、工具回归、全部 JVM 单测及隔离 SQLite 迁移。修改后应先审阅并暂存，再运行凭据检查；扫描器读取 Git 索引，不读取未暂存内容。
- `-CompileAndroidTests` 额外编译独立集成测试包；`-Release` 额外构建 release。两个开关可同时使用，所有 Gradle 任务合并为一次调用。
- 排查某个用例时，可先运行 `./gradlew.bat testDebugUnitTest --tests "完整测试类名"`；该结果只代表选中的测试，合并/发版前仍需完整检查。
- 缓存命中可以复用结果，不强制 clean 或 rerun-tasks。测试直接读取的 README、构建脚本与公式资源显式声明为输入；迁移校验所需文件显式声明为输出。
- JVM 单测禁止 Android 桩方法静默返回默认值；意外框架调用直接失败，文件、Room 和 SAF 场景由隔离 Android 集成测试实际验证。

## CI

只有 `.github/workflows/build.yml` 一份工作流：

- 普通 Develop push / main PR：工具检查、严格 JVM 单测、测试包编译和 SQLite 校验。main push 和 v* tag 不触发重复构建。
- 正式发版：本地针对性检查后先快进 main，再在 main 手动触发 release_build=true。一次 CI 同时完成 JVM/跨平台工具检查、release/R8/vital lint、原证书及 lineage 重签、16 项独立模拟器测试。
- 全部通过后，建立新 tag 并直接发布该 run 的 release-apk 构件；不重新编译、不重新签名，下载复核 SHA 和签名后归档。未通过不能发布。普通检查或 android_tests=true 不自动发布。
- 发版准备提交可明确使用 [skip ci] 抑制 Develop push 的重复检查，然后手动触发 main 发版 CI；workflow_dispatch 不受该提交标记跳过。不得借此跳过最后一次完整 CI。
- 构建一次 Gradle 调用；Windows 工具检查并行；设备任务复用构建产出的两个独立 APK，不重复编译。设备与包名双重隔离，不安装用户手机。
- main / Develop 的可信检查可保存 Gradle 缓存，PR 和其他 ref 只读。缓存不能代替必要验证；跨分支不会自动共享所有分支缓存。
- 普通过期检查可取消，正式候选使用独立不取消组。构建/设备任务超时20分钟，Windows工具任务8分钟；失败保留报告。
- 16 项 Android 测试在独立 Android 35 模拟器执行，并检查完整通过数量；可单独手动 android_tests=true。本机入口只接受已有模拟器。

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
