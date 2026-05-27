package app.markline

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import app.markline.domain.CheckinService
import app.markline.storage.SQLiteEventStore
import app.markline.ui.*
import app.markline.ui.theme.Green500
import app.markline.ui.theme.MarkLineTheme
import app.markline.util.SettingsStore

class MainActivity : ComponentActivity() {

    companion object {
        /** 通知点击后传入的 Intent Extra Key */
        const val EXTRA_OPEN_EVENT_ID = "open_event_id"
        /** Widget 点击后触发签到（1x1 / 2x2 widget） */
        const val EXTRA_TRIGGER_CHECKIN = "trigger_checkin"
    }

    private lateinit var eventStore:    SQLiteEventStore
    private lateinit var checkinService: CheckinService
    private lateinit var settingsStore: SettingsStore

    // Compose 可观察的通知跳转 eventId
    private var pendingEventId by mutableStateOf<Long?>(null)
    // Widget 触发的签到请求（消费后重置）
    private var pendingTriggerCheckin by mutableStateOf(false)

    // Android 13+ 通知权限申请
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户选择后静默继续，不强制要求 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        eventStore     = (application as MarkLineApp).eventStore
        checkinService = (application as MarkLineApp).checkinService
        settingsStore  = SettingsStore(this)

        // 申请通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 冷启动时从通知带入的 eventId
        val openEventId = intent.getLongExtra(EXTRA_OPEN_EVENT_ID, -1L)
        if (openEventId != -1L) {
            pendingEventId = openEventId
        }

        // 冷启动时从 1x1 / 2x2 Widget 带入的签到指令
        if (intent.getBooleanExtra(EXTRA_TRIGGER_CHECKIN, false)) {
            pendingTriggerCheckin = true
        }

        setContent {
            MarkLineTheme {
                AppNavigation(
                    eventStore     = eventStore,
                    checkinService = checkinService,
                    settingsStore  = settingsStore,
                    initialEventId = pendingEventId,
                    triggerCheckin = pendingTriggerCheckin,
                    onTriggerCheckinConsumed = { pendingTriggerCheckin = false }
                )
            }
        }
    }

    /**
     * App 已在前台时收到新 Intent（如点击通知、Widget）
     * 更新 Compose State 触发导航
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val eventId = intent.getLongExtra(EXTRA_OPEN_EVENT_ID, -1L)
        if (eventId != -1L) {
            pendingEventId = eventId
        }
        if (intent.getBooleanExtra(EXTRA_TRIGGER_CHECKIN, false)) {
            pendingTriggerCheckin = true
        }
    }
}

// ─── 路由定义 ─────────────────────────────────────────────

private object Routes {
    const val HOME     = "home"
    const val HISTORY  = "history"
    const val SETTINGS = "settings"
    const val DETAIL   = "detail/{eventId}"
    const val EDIT     = "edit/{eventId}"
    fun detail(id: Long) = "detail/$id"
    fun edit(id: Long)   = "edit/$id"
}

private data class BottomTab(
    val route:         String,
    val label:         String,
    val selectedIcon:  ImageVector,
    val unselectedIcon: ImageVector
)

private val bottomTabs = listOf(
    BottomTab(Routes.HOME,    "Mark", Icons.Filled.Home,    Icons.Outlined.Home),
    BottomTab(Routes.HISTORY, "Line", Icons.Filled.History, Icons.Outlined.History),
)

@Composable
private fun AppNavigation(
    eventStore:               SQLiteEventStore,
    checkinService:           CheckinService,
    settingsStore:            SettingsStore,
    initialEventId:           Long? = null,
    triggerCheckin:           Boolean = false,
    onTriggerCheckinConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 详情页、编辑页、设置页不显示底部导航
    val showBottomBar = currentRoute != Routes.DETAIL &&
        currentRoute != Routes.SETTINGS &&
        !currentRoute.orEmpty().startsWith("detail/") &&
        !currentRoute.orEmpty().startsWith("edit/")

    // 处理通知跳转：initialEventId 变化时导航到对应详情页
    // popUpTo(HOME) 确保从任何页面（包括已有详情页）都能干净跳转
    LaunchedEffect(initialEventId) {
        if (initialEventId != null) {
            navController.navigate(Routes.detail(initialEventId)) {
                popUpTo(Routes.HOME)
            }
        }
    }

    Scaffold(
        modifier     = Modifier.fillMaxSize(),
        bottomBar    = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    tonalElevation = androidx.compose.ui.unit.Dp.Unspecified
                ) {
                    bottomTabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick  = {
                                if (!selected) {
                                    navController.navigate(tab.route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState    = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label
                                )
                            },
                            label = { 
                                Text(
                                    text = tab.label, 
                                    fontSize = 11.sp,
                                    color = if (selected) MaterialTheme.colorScheme.primary 
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                ) 
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor   = MaterialTheme.colorScheme.primary,
                                selectedTextColor   = MaterialTheme.colorScheme.primary,
                                indicatorColor      = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->

        NavHost(
            navController    = navController,
            startDestination = Routes.HOME,
            modifier         = Modifier.padding(innerPadding)
        ) {
            composable(Routes.HOME) {
                MainScreen(
                    checkinService       = checkinService,
                    settingsStore        = settingsStore,
                    triggerCheckin       = triggerCheckin,
                    onTriggerCheckinConsumed = onTriggerCheckinConsumed,
                    onNavigateToHistory  = { navController.navigate(Routes.HISTORY) },
                    onNavigateToSettings = { navController.navigate(Routes.SETTINGS) }
                )
            }

            composable(Routes.HISTORY) {
                TimelineScreen(
                    eventStore   = eventStore,
                    onEventClick = { id -> navController.navigate(Routes.detail(id)) },
                    onNavigateToSettings = { navController.navigate(Routes.SETTINGS) }
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settingsStore = settingsStore,
                    eventStore    = eventStore,
                    onBack        = { navController.popBackStack() }
                )
            }

            composable(
                route     = Routes.DETAIL,
                arguments = listOf(navArgument("eventId") { type = NavType.LongType })
            ) { backStack ->
                val eventId = backStack.arguments!!.getLong("eventId")
                EventDetailScreen(
                    eventId    = eventId,
                    eventStore = eventStore,
                    onBack     = { navController.popBackStack() }
                )
            }

            composable(
                route     = Routes.EDIT,
                arguments = listOf(navArgument("eventId") { type = NavType.LongType })
            ) { backStack ->
                val eventId = backStack.arguments!!.getLong("eventId")
                EditEventScreen(
                    eventId       = eventId,
                    eventStore    = eventStore,
                    locationService = (navController.context.applicationContext as MarkLineApp).locationService,
                    settingsStore   = settingsStore,
                    onBack        = { navController.popBackStack() },
                    onSaved       = { navController.popBackStack() }
                )
            }
        }
    }
}
