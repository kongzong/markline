# MarkLine

> 点点mark，汇聚成line — 超低摩擦的现场事件记录工具

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-1.7-4285F4?logo=android)](https://developer.android.com/compose)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-29-3DDC84?logo=android)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

MarkLine 是一个极简的 Android 现场记录应用。一键记录时间、位置、录音、照片，专注于**现场记录的超低摩擦体验**——不改变拍照习惯，事后自动把照片组织成带时间与地点的记录。

---

## 功能

| 模块 | 说明 |
|------|------|
| 🔴 **一键签到** | 点击即记录，<200ms 响应，Toast + 震动即时反馈 |
| 📷 **拍照签到** | 首页一键调起相机，自动读取照片 EXIF 时间与定位，一拍即签 |
| 🖼️ **相册导入** | 批量导入水印相机等照片，按 EXIF 自动生成记录并归入时间线；照片自动复制到应用私有目录（删原图不丢），按来源去重 |
| 📍 **精确定位** | 原生 LocationManager + 逆地理编码，120 秒定位缓存，WGS-84 → GCJ-02 自动纠偏 |
| 🏘️ **地址精细化** | 显示串精度分级；粗地址自动螺旋漂移探测（80~500 米）补「XX路附近（约X米）」；兼容厂商后端把路名塞进 featureName 的情况 |
| 🎙️ **现场录音** | 前台服务录制，M4A/AAC 格式，支持补录 |
| 📜 **单列时间线** | 全设备统一单列垂直排布（全宽大卡 + 照片横幅），日期分组 + 滚动分页加载，大记录量不卡顿 |
| 🔄 **实时刷新** | 数据变化（增删改、云同步下载）自动刷新时间线并保留滚动位置；支持下拉刷新、双击顶栏回顶 |
| 🗂️ **多图记录** | 事件详情横向照片画廊，可追加、删除、全屏查看，一事件多图 |
| 🔍 **搜索与筛选** | 全文搜索；主题筛选走底部弹层（带条数统计），字号不随系统字体放大 |
| 🖼️ **主题分享长图** | 筛选主题后一键生成图文并茂海报长图（统计头 + 日期分组时间轴 + 全部照片纵向堆叠），分享微信或保存相册 |
| 🧩 **桌面小组件** | 1×1 快捷签到、2×2 大按钮签到 |
| 💾 **数据备份** | ZIP 打包导出（JSON + 照片 + 音频文件本体），一键导入合并，UUID 去重；可选完整原图或轻量压缩画质 |
| ☁️ **WebDAV 云同步** | 支持坚果云等 WebDAV 服务，记录/照片/录音/删除双向自动同步，增量对账 + LWW 冲突处理，多设备数据一致 |
| 🌙 **深色模式** | 完整适配 Material3 深色主题 |
| 🔔 **通知提醒** | 签到完成后推送通知，点击直达详情 |

---

## 使用技巧

### 🎙️ 曲线救国：录音转文字到备注

**适用场景：** 点击 Mark 签到后，手头正忙（开车、搬运、手持工具等），不方便即时录入文字。此时可先通过录音做临时记录，等有空时再将录音转为文字写入备注，既完整保留了现场信息，又便于日后检索。

MarkLine 暂未内置语音识别，但可利用系统输入法的麦克风功能实现"录音→文字"效果：

1. 在事件详情页点击**备注编辑框**，唤起输入法
2. 点击输入法键盘上的**麦克风图标**，进入语音输入模式
3. 在 MarkLine 内点击录音文件的**播放按钮**，开始播放录音
4. 输入法会"听到"播放的音频，自动将其转为文字
5. 语音输入完成后，文字即进入备注字段，可手动微调后保存

> 💡 此方案依赖输入法的语音识别能力，识别质量因输入法而异，推荐使用豆包等最新的AI语音输入法。建议在安静环境下播放，并提高手机音量以获得更佳效果。

---

## 截图

<p align="center">
  <img src="docs/images/screenshot-home-checkin.svg" width="23%" alt="首页拍照签到" />
  &nbsp;
  <img src="docs/images/screenshot-line-timeline.svg" width="23%" alt="Line 单列时间线" />
  &nbsp;
  <img src="docs/images/screenshot-detail-photos.svg" width="23%" alt="事件详情照片画廊" />
  &nbsp;
  <img src="docs/images/screenshot-settings.svg" width="23%" alt="设置页" />
</p>

---

## 快速开始

### 环境要求

- Android Studio Hedgehog (2023.1) 或更高
- JDK 17+
- Android 10+ (API 29) 设备或模拟器

### 构建

```bash
# 克隆仓库
git clone https://github.com/kongzong/markline.git
cd markline

# 构建 Debug APK
./gradlew assembleDebug

# APK 输出路径
# app/build/outputs/apk/debug/app-debug.apk
```

### 安装

```bash
# 通过 ADB 安装
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 项目结构

```
app/src/main/kotlin/app/markline/
├── MainActivity.kt                  # 入口，导航（首页/时间轴/导入/详情/设置）
├── MarkLineApp.kt                   # Application 单例，手动依赖管理
├── domain/
│   ├── Event.kt                     # 核心数据模型（uuid/label/status/photoUri）
│   ├── EventPhoto.kt                # 多图模型（event_photo 表实体）
│   ├── EventStore.kt                # 存储接口（含分页/多图/标签统计）
│   └── CheckinService.kt            # 签到核心逻辑（含拍照签到）
├── storage/
│   ├── DBHelper.kt                  # SQLiteOpenHelper，WAL 模式，v8 迁移
│   └── SQLiteEventStore.kt          # EventStore 实现（含全局数据版本号驱动响应式刷新）
├── system/
│   ├── LocationService.kt           # 原生 LocationManager（定位缓存 + 逆地理编码 + 漂移探测）
│   ├── AudioRecordService.kt        # 前台录音服务（Foreground Service）
│   ├── WebDavClient.kt              # WebDAV 客户端（OkHttp，PROPFIND/PUT/MKCOL/DELETE）
│   ├── SyncManager.kt               # 同步引擎（manifest 对账、事件 LWW、墓碑传播）
│   ├── SyncWorker.kt                # WorkManager 周期/即时同步调度（变更去抖触发）
│   ├── CheckinWidgetProvider.kt     # 1×1 签到小组件
│   ├── CheckinWidget2x2Provider.kt  # 2×2 签到小组件
│   └── WidgetCheckinReceiver.kt     # 小组件点击处理
├── ui/
│   ├── MainScreen.kt                # 超大签到按钮 + 拍照签到 + 实时状态
│   ├── TimelineScreen.kt            # 单列时间线 + 分页 + 搜索 + 主题筛选弹层（响应式刷新/下拉刷新/双击回顶）
│   ├── ImportPreviewScreen.kt       # 相册导入预览（主题点选 + 确认导入）
│   ├── EventDetailScreen.kt         # 详情页（照片画廊/查看/编辑/补录）
│   ├── PhotoThumbnail.kt            # 缩略图加载（EXIF 方向纠正） + 全屏查看
│   ├── EditEventScreen.kt           # 编辑页（补定位/补录音）
│   ├── SettingsScreen.kt            # 设置（云同步/备份画质/帮助/关于/调试）
│   └── theme/
│       ├── Theme.kt                 # Material3 亮/暗主题
│       └── Typography.kt            # 字体排版
└── util/
    ├── ExifImporter.kt              # 相册导入（EXIF 解析/去重/复制到私有目录）
    ├── PhotoStore.kt                # 照片本地化存储（私有目录 + URI 迁移 + 路径解析）
    ├── AudioFileUtil.kt             # 音频文件路径工具
    ├── SharePosterGenerator.kt      # 主题分享长图（Canvas + StaticLayout 海报生成）
    ├── BackupData.kt                # 备份数据结构（v3 ZIP，含照片本体与来源 URI）
    ├── BackupManager.kt             # 导入导出引擎（ZIP + JSON 兼容，画质可选）
    ├── CheckinNotifier.kt           # 签到通知管理
    ├── SettingsStore.kt             # DataStore 设置持久化
    ├── TimeUtil.kt                  # 时间格式化工具
    └── Wgs84ToGcj02.kt              # 坐标纠偏算法
```

---

## 技术栈

| 功能 | 技术选型 |
|------|----------|
| 语言 | Kotlin 2.0 |
| UI | Jetpack Compose + Material3 |
| 存储 | SQLite 原生（WAL 模式，手动迁移） |
| 异步 | Kotlin Coroutines |
| 设置 | DataStore Preferences |
| 定位 | 原生 LocationManager（无 GMS 依赖，定位缓存 120s） |
| 照片 | EXIF 解析 + 相册选择器（OpenDocument，无存储权限要求） |
| 录音 | MediaRecorder → Foreground Service |
| 序列化 | Gson |
| 备份 | ZIP（java.util.zip） |
| 云同步 | OkHttp + WebDAV + WorkManager（周期/去抖触发） |
| 构建优化 | R8 代码收缩 |

---

## 设计原则

- **即时响应**：签到操作 < 200ms 完成，定位/录音异步后台执行，失败不影响记录
- **极薄架构**：无 Room / DI 框架，直接 SQLiteOpenHelper，代码即文档
- **渐进增强**：先记录时间戳，再逐步补充位置、录音、照片、备注
- **数据自有**：ZIP 格式完整导出，JSON 可读，音频独立文件，不被锁定在应用内

---

## 权限

| 权限 | 用途 | 触发时机 |
|------|------|----------|
| `VIBRATE` | 签到震动反馈 | 安装时自动授予 |
| `INTERNET` | 云同步访问 WebDAV 服务 | 安装时自动授予 |
| `ACCESS_FINE_LOCATION` | GPS 精确定位 | 首次签到弹窗 |
| `ACCESS_COARSE_LOCATION` | 网络定位辅助 | 首次签到弹窗 |
| `RECORD_AUDIO` | 现场录音 | 首次签到弹窗 |
| `FOREGROUND_SERVICE` | 前台录音服务 | 录音时自动启用 |
| `POST_NOTIFICATIONS` | 签到通知 | 首次签到时弹窗 |
| `CAMERA` | 拍照签到 | 首次拍照签到时弹窗 |

---

## 数据库

当前版本：**v8**

| 版本 | 变更 |
|------|------|
| v1 | 初始表结构 |
| v2 | 列重命名 `audio_path` → `audio_file_name` |
| v3 | 新增 `label` 列 |
| v4 | 新增 `uuid` 列 + 索引，支持跨设备合并 |
| v5 | 新增 `photo_uri`、`media_source` 列，支持照片记录 |
| v6 | 新增 `event_photo` 多图表（一事件多图） |
| v7 | 新增 `source_uri` 列（照片本地化后保留来源 URI 作去重主键） |
| v8 | 新增 `updated_at` 列（同步 LWW 时间戳）+ `sync_tombstone` 墓碑表 |

---

## 开发者

- **Kong** — 产品 & 开发
- **WorkBuddy** — AI 协作开发

---

## 许可证

MIT License
