package com.example.markline

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
import com.example.markline.domain.CheckinService
import com.example.markline.storage.SQLiteEventStore
import com.example.markline.ui.*
import com.example.markline.ui.theme.Green500
import com.example.markline.ui.theme.MarkLineTheme
import com.example.markline.util.SettingsStore

class MainActivity : ComponentActivity() {

    companion object {
        /** 通知点击后传入的 Intent Extra Key */
        const val EXTRA_OPEN_EVENT_ID = "open_event_id"
    }

    private lateinit var eventStore:    SQLiteEventStore
    private lateinit var checkinService: CheckinService
    private lateinit var settingsStore: SettingsStore

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

        // 从通知点击带入的 eventId（-1L 表示没有）
        val openEventId = intent.getLongExtra(EXTRA_OPEN_EVENT_ID, -1L)

        setContent {
            MarkLineTheme {
                AppNavigation(
                    eventStore     = eventStore,
                    checkinService = checkinService,
                    settingsStore  = settingsStore,
                    initialEventId = if (openEventId != -1L) openEventId else null
                )
            }
        }
    }

    /**
     * 当 App 已在前台时点击通知，onNewIntent 会被回调
     * 更新 compose state 以跳转到详情
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
    eventStore:     SQLiteEventStore,
    checkinService: CheckinService,
    settingsStore:  SettingsStore,
    initialEventId: Long? = null
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 详情页、编辑页、设置页不显示底部导航
    val showBottomBar = currentRoute != Routes.DETAIL &&
        currentRoute != Routes.SETTINGS &&
        !currentRoute.orEmpty().startsWith("detail/") &&
        !currentRoute.orEmpty().startsWith("edit/")

    // 处理通知跳转：初次进入时导航到详情页
    LaunchedEffect(initialEventId) {
        if (initialEventId != null) {
            navController.navigate(Routes.detail(initialEventId))
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
                    audioRecorder   = (navController.context.applicationContext as MarkLineApp).audioRecorder,
                    settingsStore   = settingsStore,
                    onBack        = { navController.popBackStack() },
                    onSaved       = { navController.popBackStack() }
                )
            }
        }
    }
}
