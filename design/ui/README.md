# 数艺工坊 · 界面设计基线

**桌面控制台的界面以本目录为准。** 代码与本目录不一致时，按本目录修正代码；设计要变，先改这里，
再改代码，二者在同一次提交里。不同时维护两套视觉版本。

## 文件与权威分工

| 内容 | 唯一来源 |
| --- | --- |
| 颜色、字号、圆角、间距、动效时长 | [tokens.json](tokens.json) |
| 桌面主题 `light.css`、`dark.css` 与原型的 [tokens.css](tokens.css) | 由 [build.py](build.py) 从 `tokens.json` 生成，不能手改 |
| 概念、色彩用法、布局、六个视图、组件、动效与文案 | [SPEC.md](SPEC.md) |
| 可浏览的界面：六个视图 × 正常 / 需要关注 / 未连接 × 浅色 / 深色 × 宽窗 / 窄窗 | [index.html](index.html)，样式 [prototype.css](prototype.css)，样例与绘制 [prototype.js](prototype.js) |
| 形状、间距与字体在桌面里的实现 | `desktop/src/main/resources/com/example/datacraft/desktop/theme/base.css`，只引用令牌 |

直接用浏览器打开 `index.html` 即可浏览，不需要服务或登录；顶部的按钮只切换样例状态、主题与窗口宽度，
不连接任何后台。图标的线条路径与 `Icons.java` 相同，应用图标与 `AppIcon.java`、打包脚本绘制的一致。

## 修改流程

1. 颜色或刻度：只改 `tokens.json`，然后运行

   ```bash
   python3 -B design/ui/build.py
   ```

   它写出两个桌面主题与 `tokens.css`。新令牌要在 `base.css` 或 Java 代码里用到，否则检查会报“未使用”。
2. 布局、组件或文案：先改 `SPEC.md` 与原型（`prototype.js` / `prototype.css`），再改桌面代码。
3. 提交前运行检查（CI 的 check 阶段运行同一命令，test 阶段运行 `tests/design`）：

   ```bash
   python3 -B design/ui/build.py --check
   ```

   它在生成文件过期、`base.css` 用了未定义的 `-dc-*` 颜色、令牌无人使用，或 `base.css` 的字号、圆角、
   内边距与间隔不在刻度上（字号 12 / 13 / 15 / 18 / 20 / 24 / 28，圆角 0 / 4 / 6 / 8 / 10 / 12 / 整圆，
   间距为偶数、分隔线 1 px）时失败，并指出行号。
4. 桌面门禁 `./mvnw -Pdesktop -pl :datacraft-desktop -am verify` 在两个主题下打开全部视图，核对对比度、
   无 JavaFX 警告与不卡顿，并把截图存到 `desktop/target/ui-snapshots`，用来和原型逐一对照。

## 验收的不同层次

“设计已定义”“代码已实现”“桌面门禁通过”“打包与安装验证通过”是不同的状态，各有自己的证据：
原型与本目录说明的是目标；桌面测试与截图说明代码做到了什么；安装包的生命周期检查说明用户装上的是什么。
原型里的样例数据与固定时间只为展示，不是实际运行的结果。
