# 可靠性改进与隔离测试

备份健康状态、标签草稿、AI 诊断与 Android 集成测试自 0.6.6.6 / code 63 起提供，DB v10 / Bak v3 保持不变。CI 与 Git 传输约定见 [开发流程](../development.md)。

## 行为与边界

1. 自动备份失败不新增启动弹窗，在备份设置中持久显示错误与时间。成功写入且读回校验后清除；新文件夹状态单独开始，取消不冒充失败。
2. 标签草稿在本机原子保存，保存期间/失败有状态。恢复需快照指纹一致，不能恢复可执行预览；标签变化或文件损坏时保留文件并提示，编辑保存或明确放弃可替换。系统在写完前杀进程时只能保证上次完整文件，不保证最后一个尚在保存的按键。
3. AI 分析保留最近 20 条固定字段诊断；不存响应文本或真实标签/题目/Key。诊断失败不改变分析结果，用户可手动导出或清空。实际 AI 输出仍需用户补测；本轮验证不自动调用用户 Key。
4. `integration` 变体包名 `com.zsz.studyassistant.integration`，测试包 `.integration.test`。独立空 Application，不初始化正式应用密钥或后台任务，没有正式应用的桌面入口。测试只用独立包私有目录的随机数据库和人工文件。

## 运行入口

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/check-project.ps1 -Mode Full -Release -CompileAndroidTests
# 仅编译 APK，不连接/安装设备。

powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/run-android-tests.ps1 -Serial emulator-5554
# 仅已有模拟器：先核对 qemu 属性、独立 APK 包名及 instrumentation target，再安装测试包。
```

Android 集成测试覆盖：真实 Room v9→v10 升级、备份往返/UUID 去重/图片和关联、失败导入事务回滚、合并后重开数据库撤销、主库已提交的待确认撤销恢复、主库回滚后保留旧撤销、备份状态及取消、真实测试 SAF 权限失败/成功重试与坏读回保护、原子草稿及诊断文件恢复。测试数据库升级直接走 Room，迁移前生产备份入口不因此宣称已做真机验证。

依赖选用 Android 官方列出的 [AndroidX 测试组件](https://developer.android.com/jetpack/androidx/releases/test)。测试文件提供器按 [DocumentsProvider 文档](https://developer.android.com/guide/topics/providers/create-document-provider) 声明权限，仅存在于 integration 变体，正式 APK 不应包含。

## 手机补测清单

- 人为撤回备份目录权限后启动：不弹窗，设置主页和备份设置可见失败时间与原因；重新选择目录并成功备份后警示消失。
- AI/手工整理若干组，等“草稿已保存”后结束进程再打开：草稿恢复、勾选保留，仍需预览确认。更改标签后不应把旧草稿当作有效方案。
- 思考/快速模式分别分析、取消和制造断网：诊断编号、类型与耗时合理；导出 JSON 无题目、标签正文、响应或密钥。
- 长英文/日文/韩文页面按钮与状态没有横向裁切。

没有模拟器系统镜像时，编译成功只能证明测试可构建，不声称这些 Android 场景已执行或通过。原手机错题与旧工作区不作为测试夹具。

## v0.6.6.6 初始验证记录

本轮通过 219 项 JVM 单测（24 类）、13 项开发工具检查、隔离 SQLite 校验和正式构建。独立 Android 测试 11 项已编译入测试 APK，已核对包名和目标包隔离；SDK 目录未发现系统镜像，本轮尚未执行 Android 设备测试或手机界面验证。


## v0.6.6.7 检查

新增恢复记录故障回放、元数据保留、冲突/删除保护和损坏记录保留等5项 Android 场景，共16项。JVM严格模式231项/26类，工具测试17项。本地已通过正式构建、测试包编译与签名；Android实际结果以最终 main 手动发版CI为准。
v0.6.6.7当时的发版CI已实际通过16项模拟器测试。现行流程已改为CI产未签名候选、本地签名，统一说明见 docs/development.md；本节保留该版本历史验证结果。真实手机界面仍需用户补测。
