# 离线公式资源

`app/src/main/assets/katex` 使用 KaTeX 0.19.1（MIT，许可证保留在该目录）。

- 官方来源：https://github.com/KaTeX/KaTeX/releases/tag/v0.19.1
- 发行附件：`katex.zip`
- 官方附件 SHA256：`53fc803b117c9ccf625e232e5ce2a6571d39ca87d9345917912e0b7d08839f9e`
- 仅打包 `katex.min.js`、`katex.min.css`、LICENSE 和 woff2 字体。CSS 删除 woff/ttf 回退 URL，保留原 woff2 字体；不引入远程 CDN。
- 应用渲染保留 `trust:false`、宏展开上限 1000，并限制尺寸参数为 20em；保留 display 公式上下 padding，避免公式裁剪。

更新时替换 JS/CSS/字体整套资源，核对 CSS 引用均存在，并在本地用代表公式检查新引擎。此说明不增加 CI 或默认测试步骤。
