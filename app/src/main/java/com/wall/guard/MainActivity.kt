package com.wall.guard

import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wall.guard.appmanager.AppInfo
import com.wall.guard.appmanager.AppManager
import com.wall.guard.data.db.RuleEntity
import com.wall.guard.data.repository.RuleRepository
import com.wall.guard.filter.NetworkStateMonitor
import com.wall.guard.stats.TrafficMonitor
import com.wall.guard.ui.components.AppResolver
import com.wall.guard.ui.screens.AppListScreen
import com.wall.guard.ui.screens.AppRulesScreen
import com.wall.guard.ui.screens.ConnectionDetailScreen
import com.wall.guard.ui.screens.DashboardScreen
import com.wall.guard.ui.screens.TrafficScreen
import com.wall.guard.ui.theme.NTWallTheme
import com.wall.guard.conntrack.ConnectionTracker
import com.wall.guard.vpn.NetworkType
import com.wall.guard.vpn.VpnState
import com.wall.guard.vpn.WallService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appManager: AppManager
    @Inject lateinit var ruleRepository: RuleRepository
    @Inject lateinit var networkStateMonitor: NetworkStateMonitor
    @Inject lateinit var trafficMonitor: TrafficMonitor
    @Inject lateinit var connectionTracker: ConnectionTracker

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            WallService.enqueueStart(this)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* FGS still runs with notification hidden if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NTWallTheme {
                NTWallApp()
            }
        }
    }

    @Composable
    private fun NTWallApp() {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
        var rules by remember { mutableStateOf<Map<Int, RuleEntity>>(emptyMap()) }
        var networkType by remember { mutableStateOf(NetworkType.Unknown) }

        val vpnState by WallService.globalState.collectAsState()
        val totalTraffic by trafficMonitor.total.collectAsState()
        val perApp by trafficMonitor.perApp.collectAsState()
        val connections by connectionTracker.connections.collectAsState()
        val activeConnections by connectionTracker.activeCount.collectAsState()

        val resolver = remember {
            AppResolver { uid ->
                try {
                    appManager.getAppInfo(uid)
                } catch (_: Exception) {
                    null
                }
            }
        }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                appManager.refresh()
            }
        }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        LaunchedEffect(Unit) {
            appManager.apps.collect { loaded ->
                apps = loaded
                withContext(Dispatchers.IO) {
                    val installedUids = loaded.map { it.uid }.toSet()
                    ruleRepository.deleteRulesNotIn(installedUids)
                    for (app in loaded) {
                        ruleRepository.getOrCreateRule(app.uid, app.packageName)
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            ruleRepository.observeAll().collect { list ->
                rules = list.associateBy { r -> r.uid }
            }
        }

        LaunchedEffect(Unit) {
            networkStateMonitor.networkType.collect { networkType = it }
        }

        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (currentRoute == Routes.DASHBOARD ||
                    currentRoute == Routes.TRAFFIC ||
                    currentRoute == Routes.APPS
                ) {
                    AppBottomBar(
                        currentRoute = currentRoute,
                        onNavigate = { route ->
                            navController.navigate(route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Routes.DASHBOARD,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                composable(Routes.DASHBOARD) {
                    DashboardScreen(
                        vpnState = vpnState,
                        networkType = networkType,
                        totalTraffic = totalTraffic,
                        perApp = perApp,
                        activeConnections = activeConnections,
                        blockedAppCount = rules.count { (_, r) -> !r.allowed },
                        resolver = resolver,
                        onNavigateToTraffic = { navController.navigate(Routes.TRAFFIC) },
                        onStart = {
                            trafficMonitor.reset()
                            val intent = VpnService.prepare(context)
                            if (intent != null) vpnPrepareLauncher.launch(intent)
                            else WallService.enqueueStart(context)
                        },
                        onStop = { WallService.enqueueStop(context) }
                    )
                }

                composable(Routes.TRAFFIC) {
                    TrafficScreen(
                        vpnState = vpnState,
                        connections = connections,
                        resolver = resolver,
                        onConnectionClick = { id ->
                            navController.navigate(Routes.connection(id))
                        },
                        onStart = {
                            trafficMonitor.reset()
                            val intent = VpnService.prepare(context)
                            if (intent != null) vpnPrepareLauncher.launch(intent)
                            else WallService.enqueueStart(context)
                        }
                    )
                }

                composable(Routes.APPS) {
                    AppListScreen(
                        apps = apps,
                        rules = rules,
                        trafficByUid = perApp,
                        onToggle = { app, allowed ->
                            scope.launch(Dispatchers.IO) {
                                ruleRepository.upsert(
                                    (rules[app.uid]
                                        ?: RuleEntity(uid = app.uid, packageName = app.packageName))
                                        .copy(allowed = allowed, updatedAt = System.currentTimeMillis())
                                )
                            }
                        },
                        onEdit = { app ->
                            navController.navigate(Routes.appRules(app.uid))
                        }
                    )
                }

                composable(
                    route = Routes.APP_RULES,
                    arguments = listOf(navArgument(Routes.ARG_APP_UID) { type = NavType.IntType })
                ) { entry ->
                    val uid = entry.arguments?.getInt(Routes.ARG_APP_UID) ?: -1
                    val app = apps.firstOrNull { it.uid == uid }
                    AppRulesScreen(
                        app = app,
                        rule = rules[uid],
                        onBack = { navController.popBackStack() },
                        onSave = { updated ->
                            scope.launch(Dispatchers.IO) {
                                ruleRepository.upsert(updated)
                            }
                        }
                    )
                }

                composable(
                    route = Routes.CONNECTION_DETAIL,
                    arguments = listOf(navArgument(Routes.ARG_CONNECTION_ID) { type = NavType.LongType })
                ) { entry ->
                    val id = entry.arguments?.getLong(Routes.ARG_CONNECTION_ID) ?: -1L
                    val connection = remember(connections) {
                        connections.firstOrNull { it.id == id }
                    }
                    ConnectionDetailScreen(
                        connection = connection,
                        resolver = resolver,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}

object Routes {
    const val DASHBOARD = "dashboard"
    const val TRAFFIC = "traffic"
    const val APPS = "apps"
    const val CONNECTION_DETAIL = "connection/{id}"
    const val ARG_CONNECTION_ID = "id"
    const val APP_RULES = "app_rules/{uid}"
    const val ARG_APP_UID = "uid"

    fun connection(id: Long) = "connection/$id"
    fun appRules(uid: Int) = "app_rules/$uid"
}

@Composable
private fun AppBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    val items = listOf(
        NavItem(Routes.DASHBOARD, stringResource(R.string.nav_dashboard), Icons.Filled.Home),
        NavItem(Routes.TRAFFIC, stringResource(R.string.nav_traffic), Icons.AutoMirrored.Filled.ShowChart),
        NavItem(Routes.APPS, stringResource(R.string.nav_apps), Icons.Filled.Apps)
    )

    NavigationBar(
        containerColor = androidx.compose.ui.graphics.Color(0xFF161618)
    ) {
        items.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = { onNavigate(item.route) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = androidx.compose.ui.graphics.Color(0xFF26262B)
                )
            )
        }
    }
}

private data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)