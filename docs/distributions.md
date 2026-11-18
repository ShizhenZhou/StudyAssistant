# 主线与兼容版的共同源码

两分支保存相同的业务源码、适配实现、构建工具和文档。唯一预期的文件内容差异是 `config/distribution.properties`：main / Develop 默认为 `main`，Compatible 默认为 `compat`。不根据 Git 分支名猜测构建身份，下载源码 ZIP 也能按明确配置构建。

## 文件归属

| 位置 | 职责 |
|---|---|
| `app/src/main/` | 两发行线共用业务、UI、通知及离线渲染资源 |
| `app/src/distributions/main/java/` | 主线数据库 JSON1 扫描、标题触发器适配 |
| `app/src/distributions/compat/java/` | Android 9–15 的有界流式 JSON 扫描、标题派生适配 |
| `app/src/distributions/{main,compat}/assets/changelog/` | 两发行线各自的四语言应用更新记录；仅打包选中发行线的资源 |
| `config/distributions/{main,compat}.properties` | 各自版本号、versionCode、系统范围、显示标识与包名/标签约定 |
| `config/distribution.properties` | 当前分支的默认发行线选择 |
| `README.md`、`docs/readme/` | 共用五语言入口、功能与主线历史 |
| `docs/compatible/` | 兼容版五语言说明、独立合并历史；不重复维护共用功能介绍 |
| `tools/`、`.github/workflows/build.yml` | 共用签名/发布/构建实现，按已提交配置及发行身份校验 |

AndroidX 兼容接口和简单 SDK 判断直接共用；只有算法不同的数据库部分使用发行线源码目录。不得复制整个页面或业务目录制造第二套实现。包名/applicationId、表结构、DB v11、Bak v4 保持不变。

## 开发及验证

日常仍在 Develop 或 `codex/` 分支开发，默认使用选择器。Gradle 任务名不变，没有新增 product flavor。必要时可在同一源码树选择另一发行线，例如：

```powershell
./gradlew.bat compileDebugKotlin -Pdistribution=compat --console=plain
```

这是可选的定向验证，不是新增必跑门禁。源码目录、资源、配置和生成身份都参与任务输入，切换发行线不能复用另一发行线的 BuildConfig 或更新记录。CI 不接受本地覆盖参数；它核对请求、已提交选择器及实际 APK 的一致性。

## 正常合并

共用改动和两发行线适配尽量一起在 main 的开发流程维护。main 完成后，切到干净的 Compatible 执行 `git merge main`，保留 Compatible 选择器的 `distribution=compat`。正常祖先关系会让 Behind 归零；Ahead 中的兼容发布和合并提交无需删除。

合并前查看目标分支的新提交；如有未同步的兼容修复，先把修复纳入共用源码树。不要用整树覆盖、`merge -s ours`、重置或强制推送替代真实合并。反向合并兼容修复时主线选择器仍保留 `main`。

完成一次同步后，`git diff main Compatible --name-only` 通常只应列出选择器；独立发布期间若临时存在其他差异，需逐项说明并同步。不要为了清零 Ahead 改写历史。

## 独立版本与发布

发布哪条线就改它的 profile、应用更新记录及对应 README 历史；另一条线的版本无需同步提升。两份 profile 在两分支中都保存，main 合并到 Compatible 不会让主线版本号覆盖兼容版本号。

主线在干净已推送的 main 使用原 `release.ps1 start --version ...`；兼容版在干净已推送的 Compatible 使用 `--distribution compat`。工具同时核对选择器、分支、版本和构建 APK：主线为 Android 16+、`v...` / `SA-...`；兼容版为 Android 9–15、`compat_v...` / `SACompat-...`，不设置 Latest。差分基准仍必须属于同一发行线。

不改变已有手动 CI、原证书与 lineage、本地签名、公开下载 SHA 核验及归档流程；目录整理和普通同步不触发构建或发布。
