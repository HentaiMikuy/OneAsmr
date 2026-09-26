# OneAsmr

本地优先的 ASMR 音声作品管理与播放器（Android）。

OneAsmr 是一个**纯本地**应用：通过系统文件选择器（Storage Access Framework）授权扫描存储中的音声作品文件夹，建立本地库，手动触发 DLsite 刮削补全元数据与封面，然后离线浏览、搜索、评分与播放。**应用不含任何服务器组件**，不上传任何数据。

> 目录结构与命名约定（RJ/BJ/VJ 编号、6/8 位作品码、封面/元数据命名）采用与 kikoeru 生态兼容的方案，仅为**互通**考虑（例如同一份本地库可被兼容工具读取）。本项目不是 kikoeru-express 的移植或客户端，不包含任何服务器端代码。

## 功能清单

- **本地作品库**：SAF 授权扫描任意文件夹，自动识别 RJ/BJ/VJ 作品与 6/8 位编号，构建音轨树（mp3/wav/flac/mp4/mkv/lrc/txt/图片等）。
- **DLsite 刮削（手动）**：单条刮削与批量刮削，抓取标题、社团、声优、标签、评分与封面；全局限速（请求间隔 ≥1s、并发 ≤2、失败指数退避），遇到 403/验证码风控或解析失败时标记为可重试的失败，绝不自动触发。
- **浏览与检索**：FTS5 trigram 全文搜索（≥3 字符）、多字段排序、按进度/评分筛选；按社团 / 声优 / 标签维度浏览。
- **作品详情**：评分（1-5 星）、收听进度、评语；内嵌文本/图片查看器；文件夹失效时提示并可一键重扫修复。
- **播放器（Media3 ExoPlayer）**：播放队列、单曲/全部循环、随机、0.5x-2.0x 倍速、睡眠定时；后台播放 + 通知栏控制 + 媒体键/耳机键；音频焦点抢占自动暂停；拔耳机自动暂停。
- **续播记忆**：播放进度按三档策略恢复（≥95% 从头、3%-95% 续播、<3% 从头），跨进程持久化。
- **LRC 同步歌词**：同目录同名 .lrc 自动匹配，编码自动探测（UTF-8/GBK/Shift_JIS），点击歌词行跳转。
- **作品附带视频**：全屏播放、进度拖动、倍速、退出续播，无法解码时显示错误卡片而非崩溃。
- **设置**：主题（跟随系统/浅色/深色 + 动态取色）、扫描根目录管理、刮削语言与批量刮削入口、封面缓存占用与清理（二次确认）、续播策略、关于页（版本与开源许可）。
- **启动动画**：冷启动时与系统启动图**无缝衔接**（首帧与平台启动图同色、同尺寸、同位置），随后图标微缩放、柔光舒展与光扫掠过，OneAsmr 字标淡入上浮，最后淡出露出作品库。仅在冷启动播放一次，回前台、转屏、热深链都不会重播；深色模式下使用同一套 `values-night` 品牌底色。

## 构建说明

要求：JDK 17（项目以 `org.gradle.java.home` 指向 JDK 17 构建，Gradle wrapper 已固定为 9.7.0）、Android SDK（`local.properties` 中配置 `sdk.dir`）。

```bash
# 调试 APK（可安装、可调试）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 发布 APK（R8 混淆 + 资源压缩，已签名，可直接安装）
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk

# 单元测试（600 个用例）
./gradlew :app:testDebugUnitTest

# 直接安装到已连接的设备/模拟器
adb install -r app/build/outputs/apk/release/app-release.apk
```

**关于发布签名**：为便于本地自用，`release` 变体现在显式使用 debug key 签名（`signingConfig = signingConfigs.getByName("debug")`），保证 `assembleRelease` 产出的 APK 开箱即装。**正式发布（如上架应用商店）必须配置独立的签名**：在 `app/build.gradle.kts` 的 `android.signingConfigs` 中新建正式签名配置（keystore 自行保管、切勿提交到仓库），并将 `buildTypes.release.signingConfig` 指向它。

## 权限说明

应用声明的最小权限集（不含任何存储、定位、通讯录等敏感权限；文件访问全部通过系统文件选择器 SAF 完成）：

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | DLsite 刮削与封面下载（由网络依赖库的 manifest 合并声明） |
| `ACCESS_NETWORK_STATE` | 网络连通性判断（合并自网络依赖库） |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 后台播放的前台服务（targetSdk 34+ 要求） |
| `POST_NOTIFICATIONS` | 播放通知（Android 13+ 运行时请求；媒体会话通知本身豁免该权限，拒绝后仍可播放） |
| `WAKE_LOCK` | 锁屏后保持播放稳定 |

所有数据（作品库、评分、进度、设置）仅保存在设备本地（Room + DataStore），除用户手动触发的 DLsite 刮削请求外不产生任何网络流量。

## DLsite 刮削免责声明

- 刮削功能**仅供个人学习与自用**，请遵守 DLsite 的服务条款与当地法律法规。
- 刮削**只能由用户手动触发**（单条或批量按钮），应用不会自动发起任何请求。
- 请求已做**限速**：全局至少 1 秒间隔、最多 2 个并发、失败指数退避；遇到 403/验证码风控（BLOCKED）立即停止并报错，不绕过任何反爬机制。
- **DLsite 页面结构变动可能导致刮削失效**：解析失败会将该作品标记为可重试的失败并给出提示，应用本身不受影响。
- 刮削所得元数据与封面仅用于本地管理，请勿再分发。

## 许可证

本项目采用 **MIT License**（见下）。

选择说明：OneAsmr 为个人本地应用，代码全部原创，**未复制任何第三方代码片段**。虽然 kikoeru 生态（含 kikoeru-express，GPL-3.0）的目录/命名约定被本项目参考为互通规范，但那只涉及文件命名规则本身，不构成代码复制，因此本项目不声明 GPL。各第三方依赖（Media3、Room、Hilt、Coil、jsoup 等）保留各自许可证，详见设置页「关于」中的开源许可列表。

```
MIT License

Copyright (c) 2026 OneAsmr contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
