package br.com.strattonpay.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import br.com.strattonpay.app.data.CredentialStore
import br.com.strattonpay.app.data.StrattonApi
import br.com.strattonpay.app.notify.AppSettings
import br.com.strattonpay.app.notify.NotifyScheduler

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Início", Icons.Filled.Home),
    Tab("charge", "Cobrar", Icons.Filled.QrCode2),
    Tab("transactions", "Vendas", Icons.Filled.Receipt),
    Tab("withdraws", "Saques", Icons.Filled.AccountBalanceWallet),
    Tab("statement", "Extrato", Icons.Filled.Description),
)

private fun NavController.goTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun StrattonApp(
    store: CredentialStore,
    requireAuth: RequireAuth,
    pendingRoute: String?,
    onRouteConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var creds by remember { mutableStateOf(store.load()) }
    val current = creds
    if (current == null) {
        LoginScreen(
            saved = remember { store.remembered() },
            onLoggedIn = { newCreds, rememberMe ->
                store.save(newCreds, rememberMe)
                if (settings.notifyEnabled) {
                    settings.resetTracking()
                    NotifyScheduler.restart(context)
                }
                creds = newCreds
            },
            onForget = { store.forget() },
        )
    } else {
        LaunchedEffect(Unit) {
            if (settings.notifyEnabled) NotifyScheduler.ensure(context)
        }
        val api = remember(current) { StrattonApi(current.baseUrl, current.clientId, current.clientSecret) }
        MainScaffold(
            api = api,
            store = store,
            requireAuth = requireAuth,
            pendingRoute = pendingRoute,
            onRouteConsumed = onRouteConsumed,
            onLogout = {
                NotifyScheduler.stop(context)
                settings.resetTracking()
                store.logout()
                creds = null
            },
        )
    }
}

@Composable
private fun MainScaffold(
    api: StrattonApi,
    store: CredentialStore,
    requireAuth: RequireAuth,
    pendingRoute: String?,
    onRouteConsumed: () -> Unit,
    onLogout: () -> Unit,
) {
    val nav = rememberNavController()

    // Toque numa notificação: abre a venda/saque correspondente.
    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            nav.navigate(pendingRoute)
            onRouteConsumed()
        }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val chargeSession = remember { ChargeSession() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.goTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                indicatorColor = Gold,
                                selectedTextColor = Gold,
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") {
                HomeScreen(
                    api = api,
                    onLogout = onLogout,
                    onCharge = { nav.goTab("charge") },
                    onWithdraw = { nav.navigate("withdraw/new") },
                    onPayQr = { nav.navigate("withdraw/qr") },
                    onOpenTx = { nav.navigate("tx/$it") },
                    onSeeAllTx = { nav.goTab("transactions") },
                    onSettings = { nav.navigate("settings") },
                )
            }
            composable("charge") { ChargeScreen(api, chargeSession) }
            composable("settings") { SettingsScreen(store, onBack = { nav.popBackStack() }) }
            composable("transactions") { TransactionsScreen(api, onOpen = { nav.navigate("tx/$it") }) }
            composable("withdraws") {
                WithdrawsScreen(
                    api = api,
                    onNew = { nav.navigate("withdraw/new") },
                    onPayQr = { nav.navigate("withdraw/qr") },
                    onOpen = { nav.navigate("wd/$it") },
                )
            }
            composable("statement") {
                StatementScreen(
                    api = api,
                    onOpenTx = { nav.navigate("tx/$it") },
                    onOpenWithdraw = { nav.navigate("wd/$it") },
                )
            }
            composable("tx/{id}") { entry ->
                TransactionDetailScreen(api, entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable("wd/{id}") { entry ->
                WithdrawDetailScreen(api, entry.arguments?.getString("id").orEmpty(), requireAuth, onBack = { nav.popBackStack() })
            }
            composable("withdraw/new") {
                NewWithdrawScreen(api, requireAuth, onBack = { nav.popBackStack() }, onOpen = { id ->
                    nav.popBackStack()
                    nav.navigate("wd/$id")
                })
            }
            composable("withdraw/qr") {
                PayQrScreen(api, requireAuth, onBack = { nav.popBackStack() }, onOpen = { id ->
                    nav.popBackStack()
                    nav.navigate("wd/$id")
                })
            }
        }
    }
}
