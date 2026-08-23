# Study Assistant 📚

个人自用安卓应用：**拍照搜题 → AI 解答 → 错题整理**（面向理工科大学题目）。

## 功能（v0.2）

- 📷 **拍照解答**：相机拍照 → 直接发送给 **DeepSeek 视觉模型**（deepseek-v4-flash-vision-exp），识别题目并给出分步解答（公式零损失，无需单独 OCR）
- ⌨️ **手动输入**：粘贴/手输题目 → DeepSeek 文本模型（deepseek-v4-pro）解答
- 📚 **错题本**：本地保存题目+解答（Room），随时回看、删除

## 技术栈

Kotlin · Jetpack Compose · MVVM · Room · Retrofit/OkHttp · CameraX · 阿里云 Maven 镜像

## 构建

```bash
# 首次构建会自动下载依赖（走阿里云镜像加速）
gradlew.bat assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 配置 API Key（不入库）

编辑 `secrets.properties`（已被 .gitignore 排除）：

```properties
DEEPSEEK_API_KEY=sk-xxx
```

Key 获取：https://platform.deepseek.com（账号需开通视觉模型权限）

## 环境要求

- JDK 17+（本机 JDK 21）
- Android SDK（compileSdk 37，本机已装 android-37.0）
