# MarkLine v0.1

> 超低摩擦的现场事件记录工具

## 快速导入

1. 打开 Android Studio（推荐 Hedgehog 2023.1 或更高）
2. **File → Open** → 选择此目录（`C:\tmp\markline`）
3. 等待 Gradle Sync 完成（首次需要联网下载依赖，约 2~5 分钟）
4. 连接 Android 10+ 真机或模拟器，点击 Run

## 项目结构

```
app/src/main/kotlin/com/example/markline/
├── MainActivity.kt          # 入口，底部导航（签到/历史/设置）
├── MarkLineApp.kt           # Application 单例，手动依赖管理
├── domain/
│   ├── Event.kt             # 核心数据模型
│   ├── EventStore.kt        # 存储接口
│   └── CheckinService.kt    # 签到核心逻辑
├── storage/
│   ├── DBHelper.kt          # SQLiteOpenHelper，WAL 模式
│   └── SQLiteEventStore.kt  # 实现
├── system/
│   ├── LocationService.kt   # FusedLocation（缓存优先）
│   ├── AudioRecorder.kt     # MediaRecorder AAC/M4A
│   ├── CheckinWidgetProvider.kt  # AppWidget
│   └── WidgetCheckinReceiver.kt  # Widget 点击处理
├── ui/
│   ├── MainScreen.kt        # 超大签到按钮
│   ├── TimelineScreen.kt    # 时间轴历史
│   ├── SettingsScreen.kt    # 设置页
│   └── theme/Theme.kt       # Material3 主题
└── util/
    ├── SettingsStore.kt     # DataStore 设置
    └── TimeUtil.kt          # 时间格式化工具
```

## 技术选型

| 功能 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material3 |
| 存储 | SQLite 原生（WAL 模式） |
| 后台任务 | Coroutine |
| 设置 | DataStore Preferences |
| 定位 | FusedLocationProvider |
| 录音 | MediaRecorder (AAC/M4A) |
| Widget | AppWidget (1x1) |

## 核心原则

- **签到立即响应**（< 200ms）：写入 Event 后立即 Toast + 震动
- **渐进增强**：定位、录音全部异步后台执行，失败不影响签到记录
- **极薄架构**：无 Room / DI 框架，直接 SQLiteOpenHelper

## 权限说明

| 权限 | 时机 |
|------|------|
| VIBRATE | 安装时自动授予（普通权限） |
| ACCESS_FINE_LOCATION | 首次签到时弹出请求 |
| RECORD_AUDIO | 首次签到时弹出请求 |

## 定制说明

- 修改 Package 名：全局替换 `com.example.markline` 为你的包名
- 修改 App 名称：编辑 `res/values/strings.xml`
- 录音时长默认 30 秒，用户可在设置页调整（5~300 秒）

## v0.2 预留

代码中已注释预留位置：
- 照片附件
- 项目归类
- JSON 导出
- 云同步
