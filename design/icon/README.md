# OneAsmr 图标设计（设计源 + 评审工具）

这个目录是图标/启动图**设计的唯一源文件**：应用里的 Android 矢量资源
（`app/src/main/res/drawable/ic_launcher_*.xml`、`ic_splash.xml`）都是从这里的
SVG 手工转写的，改设计请先改 SVG、重出评审图、确认后再同步到 res/。

## 最终采用

**L · 霓虹声线（「暗夜声线」）+ C3 完成度**：
[`oneasmr-style-l-neon.svg`](oneasmr-style-l-neon.svg) 是概念稿，
[`oneasmr-c3-nightwave.svg`](oneasmr-c3-nightwave.svg) 是最终落地的完成度版本
（四层底 + 三层辉光波形）。落地的对应关系：

| 设计 | Android 资源 |
| --- | --- |
| C3 的四层底（主渐变 / 冷光晕 / 暖光晕 / 顶部柔光 + 暗角） | `res/drawable/ic_launcher_background.xml` |
| C3 的波形（13/7/4 三层描边辉光） | `res/drawable/ic_launcher_foreground.xml` |
| 同一波形的单色版（去掉渐变，仅用 alpha） | `res/drawable/ic_launcher_monochrome.xml` |
| 同一波形、288dp 启动画布 | `res/drawable/ic_splash.xml` |
| 平色代表值（窗口/启动底色） | `res/values{,-night}/colors.xml` → `ic_launcher_background` |

`ic_splash.xml` 同时被冷启动动画复用（`ui/launch/LaunchSplash.kt`），
所以"平台启动图最后一帧 == 应用内动画第一帧"的无缝契约依赖二者共用这一张 drawable
与 `@color/ic_launcher_background`，改色/改形时两边必须一起改。

## 怎么重出评审图

```bash
bash design/icon/render.sh          # 渲染全部候选 + preview.png（需要 Chrome）
python3 design/icon/make-preview.py # 只重新生成 preview.html
```

`render.sh` 用无头 Chrome 渲染；PNG 都是可再生成的中间产物，因此不进版本库
（见 [.gitignore](.gitignore)）。

## 目录里的东西

- `oneasmr-c3-nightwave.svg` —— **最终采用**（暗夜声线 · 完成度版）
- `oneasmr-style-l-neon.svg` —— 概念稿
- `oneasmr-c1-wavefield-cool.svg` / `oneasmr-c2-wavefield-warm.svg` / `oneasmr-c4-radialwhite.svg` —— 同批其他商业级方案（未采用）
- 其余 `oneasmr-*.svg` —— 历次探索稿（角色、耳机、黑胶、月牙、放射、螺旋、栅格、点阵、等距、卡带、液态玻璃…），保留供以后参考
- `legacy-current.svg` —— 旧图标的机械转换件（评审时做"改前/改后"对照用）
- `make-preview.py` / `render.sh` / `legacy-icon.py` —— 评审图与转换脚本
