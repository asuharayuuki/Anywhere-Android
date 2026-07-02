package com.argsment.anywhere.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.argsment.anywhere.R
import com.argsment.anywhere.ui.home.HomeScreen
import com.argsment.anywhere.ui.proxy.ChainListScreen
import com.argsment.anywhere.ui.proxy.ProxyListScreen
import com.argsment.anywhere.ui.settings.SettingsScreen
import com.argsment.anywhere.viewmodel.VpnViewModel
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object ProxiesRoute
@Serializable object ChainsRoute
@Serializable object SettingsRoute

data class TopLevelRoute(
    val titleResId: Int,
    val route: Any,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@Composable
fun AppNavigation(viewModel: VpnViewModel) {
    val navController = rememberNavController()

    // Switch to the Proxies tab when a deep link arrives so ProxyListScreen can consume it.
    val pendingDeepLink by viewModel.pendingDeepLinkUrl.collectAsState()
    LaunchedEffect(pendingDeepLink) {
        if (pendingDeepLink != null) {
            navController.navigate(ProxiesRoute) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val topLevelRoutes = listOf(
        TopLevelRoute(
            titleResId = R.string.home,
            route = HomeRoute,
            selectedIcon = Icons.Filled.Home,
            unselectedIcon = Icons.Outlined.Home
        ),
        TopLevelRoute(
            titleResId = R.string.proxies,
            route = ProxiesRoute,
            selectedIcon = ImageVector.vectorResource(R.drawable.ic_network_filled),
            unselectedIcon = ImageVector.vectorResource(R.drawable.ic_network_outlined)
        ),
        TopLevelRoute(
            titleResId = R.string.chains,
            route = ChainsRoute,
            selectedIcon = Icons.Filled.Link,
            unselectedIcon = Icons.Filled.Link
        ),
        TopLevelRoute(
            titleResId = R.string.settings,
            route = SettingsRoute,
            selectedIcon = Icons.Filled.Settings,
            unselectedIcon = Icons.Outlined.Settings
        )
    )

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            NavigationBar {
                topLevelRoutes.forEach { topLevelRoute ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.hasRoute(topLevelRoute.route::class)
                    } == true

                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (selected) topLevelRoute.selectedIcon else topLevelRoute.unselectedIcon,
                                contentDescription = stringResource(topLevelRoute.titleResId)
                            )
                        },
                        label = { Text(stringResource(topLevelRoute.titleResId)) },
                        selected = selected,
                        onClick = {
                            navController.navigate(topLevelRoute.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute
        ) {
            composable<HomeRoute> {
                HomeScreen(viewModel = viewModel, contentPadding = innerPadding)
            }
            composable<ProxiesRoute> {
                Box(modifier = Modifier.padding(innerPadding)) {
                    ProxyListScreen(viewModel = viewModel)
                }
            }
            composable<ChainsRoute> {
                Box(modifier = Modifier.padding(innerPadding)) {
                    ChainListScreen(viewModel = viewModel)
                }
            }
            composable<SettingsRoute> {
                Box(modifier = Modifier.padding(innerPadding)) {
                    SettingsScreen(viewModel = viewModel)
                }
            }
        }

        // Routing Rules Updated alert — surfaces orphaned rule-set assignments that
        // were silently re-pointed to Default after a referenced proxy was deleted.
        // Mirrors iOS ContentView's "Routing Rules Updated" alert.
        val orphanedNames by viewModel.orphanedRuleSetNames.collectAsState()
        if (orphanedNames.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { viewModel.clearOrphanedRuleSetNames() },
                title = { Text(stringResource(R.string.routing_rules_updated_title)) },
                text = {
                    Text(stringResource(R.string.routing_rules_updated_message,
                        orphanedNames.joinToString(", ")))
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearOrphanedRuleSetNames() }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            )
        }

        val pendingRuleSetLinks by viewModel.pendingRuleSetLinks.collectAsState()
        if (pendingRuleSetLinks.isNotEmpty()) {
            val link = pendingRuleSetLinks.first()
            var error by remember(link) { mutableStateOf<String?>(null) }
            var subscribing by remember(link) { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            AlertDialog(
                onDismissRequest = { viewModel.clearPendingRuleSetLinks() },
                title = { Text(stringResource(R.string.subscribe_rule_set)) },
                text = {
                    if (error != null) {
                        Text(error!!)
                    } else if (subscribing) {
                        CircularProgressIndicator()
                    } else {
                        Text("Subscribe to: $link?")
                    }
                },
                confirmButton = {
                    if (error == null && !subscribing) {
                        TextButton(onClick = {
                            subscribing = true
                            scope.launch {
                                try {
                                    val body = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        val conn = java.net.URL(link).openConnection() as java.net.HttpURLConnection
                                        conn.connectTimeout = 15000
                                        conn.readTimeout = 30000
                                        try {
                                            val code = conn.responseCode
                                            if (code !in 200..299) throw IllegalStateException("HTTP $code")
                                            conn.inputStream.bufferedReader().use { it.readText() }
                                        } finally {
                                            conn.disconnect()
                                        }
                                    }
                                    val parsed = com.argsment.anywhere.data.rules.RoutingRuleParser.parse(body)
                                    val name = if (parsed.name.isNotEmpty()) parsed.name else "Subscription"
                                    
                                    val created = viewModel.ruleSetRepository.addCustomRuleSet(name, parsed.rules, link)
                                    parsed.routing.assignmentId?.let { assignId ->
                                        viewModel.ruleSetRepository.updateAssignment(
                                            viewModel.ruleSetRepository.ruleSets.value.first { it.id == created.id },
                                            assignId
                                        )
                                    }
                                    viewModel.syncRoutingConfigurationToNE()
                                    viewModel.clearPendingRuleSetLinks()
                                } catch (e: Exception) {
                                    error = e.message ?: "Unknown error"
                                } finally {
                                    subscribing = false
                                }
                            }
                        }) {
                            Text(stringResource(R.string.subscribe))
                        }
                    } else if (error != null) {
                        TextButton(onClick = { viewModel.clearPendingRuleSetLinks() }) {
                            Text(stringResource(R.string.ok))
                        }
                    }
                },
                dismissButton = {
                    if (!subscribing && error == null) {
                        TextButton(onClick = { viewModel.clearPendingRuleSetLinks() }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            )
        }
    }
}
