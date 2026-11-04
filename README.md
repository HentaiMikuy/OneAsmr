# OneAsmr

本地优先的 ASMR 音声作品管理与播放器（Android）。

OneAsmr 是一个**纯本地**应用：通过系统文件选择器（Storage Access Framework）授权扫描存储中的音声作品文件夹，建立本地库，手动触发 DLsite 刮削（失败时可选 asmr.one 备用源）补全元数据与封面，然后离线浏览、搜索、评分与播放。**应用不含任何服务器组件**，不上传任何数据。

> 目录结构与命名约定（RJ/BJ/VJ 编号、6/8 位作品码、封面/元数据命名）采用与 kikoeru 生态兼容的方案，仅为**互通**考虑（例如同一份本地库可被兼容工具读取）。本项目不是 kikoeru-express 的移植或客户端，不包含任何服务器端代码。

## 功能清单

- **本地作品库**：通过 SAF 授权扫描任意文件夹，自动识别 RJ/BJ/VJ 作品与 6/8 位编号，构建音轨树——音频 mp3/wav/flac/ogg/opus/aac/m4a、视频 mp4/webm/mkv/mov、文本 lrc/vtt/srt/ass/txt、图片 jpg/jpeg/png/webp；重扫为增量式，并报告新增/更新/失效数量。
- **DLsite 刮削（手动）**：单条刮削与批量刮削，抓取标题、社团、声优、标签、年龄分级、评分、销量、价格与封面；全局限速（请求间隔 ≥1s、并发 ≤2、失败指数退避），遇到 403/验证码风控或解析失败时标记为可重试的失败，绝不自动触发。
- **asmr.one 备用源**：DLsite 刮削失败时改用 asmr.one 的 JSON API 补全（仅 RJ 作品，镜像地址可自定义）；其请求与 DLsite 共用同一套全局限速配额，同样只在手动刮削时发起。
- **浏览与检索**：FTS5 trigram 全文搜索（≥3 字符，支持 RJ 编号直查与标题/社团/标签/CV 检索）；9 种排序字段（编号、发售日期、评分、销量、评论数、价格、均价、标题、随机），除「随机」外都可切换升降序；筛选支持已评分、收听进度与年龄分级（未分级/全年龄/R15/R18）；可按社团、标签、声优维度浏览该维度的全部作品。
- **作品详情**：评分（1-5 星）、收听进度、评语、分级与销量等刮削元数据；内嵌文本/图片查看器；文件夹失效时提示并可一键重扫修复。
- **单文件库（单档）**：把下载好的散装音频/视频文件夹授权为「单文件库」根目录，与作品库分开独立管理；从文件名自动解析 YouTube 视频 ID 补全标题、频道与封面，文件名里没有 ID 时可粘贴链接手动关联；支持网格/列表双视图、按加入时间/标题/时长双向排序，网格卡片长按唤出操作菜单，失效条目可一键移除。
- **收藏夹**：单文件库之上的归属体系，系统「收藏」置顶，可新建自定义收藏夹，爱心按钮一键切换收藏；一个文件可同时属于多个收藏夹，也可多选后批量加入所选收藏夹，重复成员自动忽略。
- **播放器（Media3 ExoPlayer）**：播放队列、四档循环（顺序 → 列表循环 → 单曲循环 → 播完暂停）、随机、0.5x-2.0x 倍速（0.1x 步进，另附 6 档常用预设）、睡眠定时（15/30/45/60 分钟或播完当前曲）；后台播放 + 通知栏控制 + 媒体键/耳机键；音频焦点抢占自动暂停；拔耳机自动暂停。
- **续播记忆**：播放进度按三档策略恢复（≥95% 从头、3%-95% 续播、<3% 从头），跨进程持久化。
- **收听统计**：本地实际播放时长、连续播放次数、最近 7 天趋势、收听时段分布、常听作品排行与最近收听；暂停和缓冲不计时长，数据仅保存在本机。
- **LRC 同步歌词**：同目录同名 .lrc 自动匹配，编码先读 BOM、无 BOM 时交给 juniversalchardet 探测（GBK/Shift_JIS/Big5 等），点击歌词行跳转。
- **视频播放（作品附带视频 / 单档视频）**：全屏沉浸播放；手势为单击呼出控制层、双击左右半屏 ±10 秒、长按任意位置 3x 加速（松手恢复）、横向滑动拖动进度（松手才生效）、左半屏上下滑调亮度 / 右半屏调音量；四档播放策略（顺序 / 列表循环 / 单集循环 / 播完暂停，独立于音频循环偏好）、倍速、退出续播；可在设置里开启「视频后台续播」（关屏或切后台后仅解码音频继续播放）；无法解码时显示错误卡片而非崩溃。
- **内容安全（NSFW 模式）**：首次启动弹出一次选择；关闭 NSFW 后按年龄分级对封面与曲名打码（未分级/R15/R18 视为敏感，仅「全年龄」豁免），设置页可随时改回；开启「绝对限制」则忽略分级、全部作品一律打码，同时锁死 NSFW 开关不可再开；冷启动动画结束后若仍处于 NSFW 模式，会提醒一次并可直接切到绝对限制或关闭。
- **设置**：按「外观 / 播放 / 媒体库 / 刮削 / 存储 / 关于」分区筛选并支持全库搜索——外观（主题模式、动态取色、启动动画）、播放（续播策略、视频后台续播）、媒体库（根文件夹管理、NSFW 模式、绝对限制）、刮削（刮削语言、asmr.one 备用源与镜像地址、批量刮削入口）、存储（封面缓存清理、清除全部记忆播放位置）、关于（版本、检查更新、开源许可）。
- **启动动画**：冷启动时与系统启动图**无缝衔接**（首帧与平台启动图同色、同尺寸、同位置），随后图标微缩放、柔光舒展与光扫掠过，OneAsmr 字标淡入上浮，最后停留 1.5 秒再淡出露出作品库（动画本身约 0.7 秒，总时长约 2.4 秒）。仅在冷启动播放一次，回前台、转屏、热深链都不会重播；深色模式下使用同一套 `values-night` 品牌底色。可在设置页「外观」分区关闭（默认开启）：关闭后系统启动窗口结束即直接进入主界面，下一次冷启动生效。

## 构建说明

要求：JDK 17（`compileOptions` 与 Kotlin `jvmTarget` 均为 17）、Android SDK（`local.properties` 中配置 `sdk.dir`）。Gradle wrapper 固定在 9.7.0；`compileSdk` / `targetSdk` 为 37，`minSdk` 为 26（Android 8.0）。

```bash
# 调试 APK（可安装、可调试）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 发布 APK（R8 混淆 + 资源压缩，已签名，可直接安装）
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk

# 单元测试（约 880 个用例）
./gradlew :app:testDebugUnitTest

# 直接安装到已连接的设备/模拟器
adb install -r app/build/outputs/apk/release/app-release.apk
```

**关于发布签名**：`release` 变体按环境变量决定签名方式——未提供 `RELEASE_KEYSTORE_B64` 等四个变量时回退到 debug key（`signingConfigs.getByName("debug")`），保证本地 `assembleRelease` 产出的 APK 开箱即装；CI 上四个 Secret 齐备时改用独立的 `ciRelease` 配置（把 base64 还原为 `build/release.keystore` 后签名）。**正式发布必须使用独立密钥**，否则升级包与已安装版本的签名不一致，系统会拒绝覆盖安装。密钥生成与 GitHub Secrets 配置见下文「签名密钥（重要）」。

## 权限说明

应用声明的最小权限集（不含任何存储、定位、通讯录等敏感权限；文件访问全部通过系统文件选择器 SAF 完成）：

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | DLsite / asmr.one 刮削、封面下载、GitHub Releases 更新检查与安装包下载 |
| `ACCESS_NETWORK_STATE` | 合并自依赖库（应用代码本身不读取网络状态） |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 后台播放的前台服务（targetSdk 34+ 要求） |
| `POST_NOTIFICATIONS` | 播放通知（Android 13+ 运行时请求；媒体会话通知本身豁免该权限，拒绝后仍可播放） |
| `WAKE_LOCK` | 锁屏后保持播放稳定 |
| `REQUEST_INSTALL_PACKAGES` | 应用内自更新时把下载好的 APK 交给系统安装器（仍需用户在系统设置里授权一次「安装未知应用」） |
| `RECEIVE_BOOT_COMPLETED` | 合并自 WorkManager：设备重启后恢复已排队的后台任务（如扫描作业） |

> 构建时 AndroidX 还会自动注入一条应用私有、签名级的 `com.oneasmr.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，仅用于运行时注册的广播接收器，不对用户数据产生额外访问。

所有数据（作品库、评分、进度、收听统计、设置）仅保存在设备本地（Room + DataStore），除用户手动触发的刮削请求与更新检查外不产生任何网络流量。

## DLsite / asmr.one 刮削免责声明

- 刮削功能**仅供个人学习与自用**，请遵守 DLsite 与 asmr.one 的服务条款与当地法律法规。
- 刮削**只能由用户手动触发**（单条或批量按钮），应用不会自动发起任何请求。
- 请求已做**限速**：全局至少 1 秒间隔、最多 2 个并发、失败指数退避；遇到 403/验证码风控（BLOCKED）立即停止并报错，不绕过任何反爬机制。asmr.one 备用源与 DLsite 共用这一套全局配额。
- **站点结构变动可能导致刮削失效**：解析失败会将该作品标记为可重试的失败并给出提示，应用本身不受影响。
- asmr.one 仅作为 DLsite 失败后的**兜底补全**，只处理 RJ 作品，不替代 DLsite 的首选地位；镜像地址可自定义，但同样受上述限速约束。
- 刮削所得元数据与封面仅用于本地管理，请勿再分发。

## 自动更新与发布

应用内置自更新（设置页「关于」→「检查更新」），更新源为 GitHub Releases。整体流程：

1. 准备发布时才递增 `app/build.gradle.kts` 中的 `versionCode` / `versionName`（一个版本可以包含多笔改动；日常开发、本地自测的改动**不要**递增，避免一笔改动占掉一个版本号）。
2. 提交并推送一个版本标签（标签名即版本号，需与 `versionName` 对应）：

   ```bash
   git tag v0.2.6
   git push origin v0.2.6
   ```

3. 可选但推荐：为新版本写一份精选说明 `.github/release-notes/<版本号>.md`（文件名**不带** `v` 前缀，如 `.github/release-notes/0.2.6.md`），随标签一起推送。

4. GitHub Actions（`.github/workflows/release.yml`）自动执行：校验签名密钥 → `assembleRelease` → 重命名产物 → 生成更新说明 → 创建 Release 并附带 APK。更新说明的取值规则是「**精选说明优先**」：存在 `.github/release-notes/<版本号>.md` 就用它，否则按该标签以来的提交标题自动分组生成（`feat` → ✨新增、`fix` → 🐛修复、其余 → 🔧其他）。Release 说明会作为应用内「检查更新」展示的更新日志。

5. 已发布 Release 的说明写错时不必重新打标签：修改 `.github/release-notes/<版本号>.md` 后推送到 `main`，`.github/workflows/sync-release-notes.yml` 会把新内容同步到对应 Release（也可在 Actions 里手动触发并指定标签）。

6. 旧版本 App 启动时静默检查一次；发现新版本 → 设置页「检查更新」行显示「发现新版本」。点击「去下载」→ 下载完成 →「安装」（首次安装需在系统设置中授权「安装未知应用」一次）。

### 签名密钥（重要）

App 的自动更新依赖**签名一致**：升级包必须与已安装版本使用同一密钥签名，否则系统拒绝覆盖安装（只能卸载重装）。发布用专用密钥：

```bash
# 生成（仅一次，妥善保管，勿提交仓库）
keytool -genkeypair -v -keystore release.keystore \
  -alias oneasmr -keyalg RSA -keysize 2048 -validity 10000

# 转为 base64 以备存入 GitHub Secrets
base64 -i release.keystore   # macOS/Linux
```

在 GitHub 仓库 Settings → Secrets and variables → Actions 中配置四个密钥：

| Secret | 内容 |
| --- | --- |
| `RELEASE_KEYSTORE_B64` | `release.keystore` 的 base64 |
| `RELEASE_KEYSTORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | 别名（如 `oneasmr`） |
| `RELEASE_KEY_PASSWORD` | 密钥密码 |

本地开发不设置这些环境变量时，`assembleRelease` 自动回退用 debug 密钥签名（可直接安装，仅用于自测；覆盖安装仅在同一机器生成的包之间成立）。

### 检查更新实现要点

- 检查：`https://api.github.com/repos/HentaiMikuy/OneAsmr/releases/latest`（公开仓库无需鉴权；未登录限流 60 次/小时/IP，够用）。
- 版本比较：语义化比较 `major.minor.patch`，标签如 `v0.2.6`。
- 下载：交给 Android 系统 DownloadManager，写入应用专属的 `getExternalFilesDir(DIRECTORY_DOWNLOADS)/update/`，显示系统下载通知；网络中断时由系统等待网络或重试，不受应用请求的 30 秒总超时限制。
- 恢复：保存下载任务编号和版本信息，重新进入设置页或重启应用后恢复进度及安装入口；“后台继续”后仍可点击检查更新一栏查看下载或安装。
- 校验：仅接受系统标记为成功的任务，核对安装包大小；GitHub 返回 SHA-256 时同时核对摘要。失败任务及文件会清理，重试直接重新下载；覆盖安装成功后下次进入设置会清理旧任务和安装包。
- 安装：FileProvider（`${applicationId}.fileprovider`）+ `ACTION_VIEW` 交给系统安装器；需要 `REQUEST_INSTALL_PACKAGES` 权限与一次「安装未知应用」授权。
- 授权：使用 `canRequestPackageInstalls()` 检查权限，授权返回后继续安装；取消授权或选择稍后安装时保留安装包和入口。

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
