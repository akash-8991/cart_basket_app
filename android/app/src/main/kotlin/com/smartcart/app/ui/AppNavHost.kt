package com.smartcart.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smartcart.app.feature.auth.ForgotPasswordScreen
import com.smartcart.app.feature.auth.ForgotUserIdScreen
import com.smartcart.app.feature.auth.LoginScreen
import com.smartcart.app.feature.auth.RegisterScreen
import com.smartcart.app.feature.auth.ReinstallScreen
import com.smartcart.app.feature.auth.ResetPasswordScreen
import com.smartcart.app.feature.auth.UnlockScreen
import com.smartcart.app.feature.auth.WelcomeScreen
import com.smartcart.app.feature.chat.ChatHistoryScreen
import com.smartcart.app.feature.chat.ChatScreen
import com.smartcart.app.feature.comparison.ComparisonScreen
import com.smartcart.app.feature.history.HistoryConnectScreen
import com.smartcart.app.feature.history.ImportProgressScreen
import com.smartcart.app.feature.history.ImportReviewScreen
import com.smartcart.app.feature.history.OrdersScreen
import com.smartcart.app.feature.home.HomeScreen
import com.smartcart.app.feature.home.MonthDetailScreen
import com.smartcart.app.feature.settings.ActivityScreen
import com.smartcart.app.feature.settings.DeveloperScreen
import com.smartcart.app.feature.settings.HelpScreen
import com.smartcart.app.feature.settings.NotificationSettingsScreen
import com.smartcart.app.feature.settings.PrivacyScreen
import com.smartcart.app.feature.settings.ProvidersScreen
import com.smartcart.app.feature.settings.SessionsScreen
import com.smartcart.app.feature.settings.SettingsScreen
import com.smartcart.app.feature.suggestions.SuggestionsScreen
import com.smartcart.app.feature.task.ApproveScreen
import com.smartcart.app.feature.task.AutomationConsentScreen
import com.smartcart.app.feature.task.CartReadyScreen
import com.smartcart.app.feature.task.ManualFallbackScreen
import com.smartcart.app.feature.task.TaskScreen
import com.smartcart.app.ui.common.ActiveTaskBar
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.data.AnalyticsEventName
import com.smartcart.model.AccountState
import com.smartcart.model.Category
import com.smartcart.model.ProviderKey
import com.smartcart.model.StopReason
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object Routes {
    const val WELCOME = "welcome"
    const val REGISTER = "auth/register"
    const val LOGIN = "auth/login"
    const val UNLOCK = "auth/unlock"
    const val REINSTALL = "auth/reinstall"
    const val FORGOT_PASSWORD = "auth/forgot-password"
    const val RESET_PASSWORD = "auth/reset-password/{challengeId}"
    const val FORGOT_USER_ID = "auth/forgot-user-id"
    const val HOME = "home"
    const val MONTH = "insights/month/{month}"
    const val CHAT = "chat/{category}"
    const val CHAT_HISTORY = "chat-history"
    const val COMPARISON = "comparison/{quoteId}"
    const val CONSENT = "consent/automation/{provider}?quoteId={quoteId}"
    const val APPROVE = "task/{taskId}/approve"
    const val TASK = "task/{taskId}"
    const val READY = "task/{taskId}/ready"
    const val MANUAL = "manual/{provider}?quoteId={quoteId}&taskId={taskId}"
    const val SUGGESTIONS = "suggestions"
    const val HISTORY_CONNECT = "history/connect"
    const val HISTORY_IMPORT = "history/import/{sessionId}"
    const val HISTORY_REVIEW = "history/review/{sessionId}"
    const val ORDERS = "history/orders"
    const val SETTINGS = "settings"
    const val SET_PROVIDERS = "settings/providers"
    const val SET_PRIVACY = "settings/privacy"
    const val SET_SESSIONS = "settings/sessions"
    const val SET_NOTIFICATIONS = "settings/notifications"
    const val SET_ACTIVITY = "settings/activity"
    const val HELP = "help"
    const val DEVELOPER = "settings/developer"

    fun month(m: String) = "insights/month/$m"
    fun chat(c: Category) = "chat/${c.key}"
    fun comparison(id: String) = "comparison/$id"
    fun consent(p: ProviderKey, quoteId: String) = "consent/automation/${p.key}?quoteId=$quoteId"
    fun approve(taskId: String) = "task/$taskId/approve"
    fun task(taskId: String) = "task/$taskId"
    fun ready(taskId: String) = "task/$taskId/ready"
    fun manual(p: ProviderKey, quoteId: String? = null, taskId: String? = null) =
        "manual/${p.key}?quoteId=${quoteId.orEmpty()}&taskId=${taskId.orEmpty()}"
    fun resetPassword(challengeId: String) = "auth/reset-password/$challengeId"
    fun historyImport(id: String) = "history/import/$id"
    fun historyReview(id: String) = "history/review/$id"
}

private fun providerArg(s: String?) = ProviderKey.entries.firstOrNull { it.key == s }

@Composable
fun AppNavHost(pendingLink: StateFlow<Uri?>, consumeLink: () -> Unit, navController: NavHostController = rememberNavController()) {
    val c = LocalAppContainer.current
    val account by c.auth.state.collectAsStateWithLifecycle()
    val activeTask by c.tasks.activeTask.collectAsStateWithLifecycle()
    val link by pendingLink.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val start = when (account) {
        is AccountState.SignedIn -> Routes.HOME
        is AccountState.Remembered -> Routes.UNLOCK
        else -> Routes.WELCOME
    }

    // Leaving the signed-in state (logout, revoked or expired session) always returns to Welcome.
    LaunchedEffect(account) {
        if (account is AccountState.SignedOut) {
            val current = navController.currentDestination?.route
            if (current != null && !current.startsWith("auth/") && current != Routes.WELCOME) {
                navController.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
            }
        }
    }

    // Deep links from notifications are handled only once signed in.
    LaunchedEffect(link, account) {
        val uri = link ?: return@LaunchedEffect
        if (account !is AccountState.SignedIn) return@LaunchedEffect
        c.analytics.track(AnalyticsEventName.NOTIFICATION_OPENED, mapOf("notification_type" to if (uri.host == "task") "cart_ready" else "import_complete"))
        val seg = uri.pathSegments
        when (uri.host) {
            "task" -> seg.firstOrNull()?.let { navController.navigate(Routes.task(it)) }
            "history" -> if (seg.size >= 2 && seg[0] == "import") navController.navigate(Routes.historyImport(seg[1]))
            "insights" -> if (seg.size >= 2) navController.navigate(Routes.month(seg[1]))
            "home" -> navController.navigate(Routes.HOME)
            // Handoff tokens are validated server-side in Phase 3; the mock opens home.
            else -> navController.navigate(Routes.HOME)
        }
        consumeLink()
    }

    fun signedIn() = navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
    val back: () -> Unit = { navController.popBackStack() }

    Column(Modifier.fillMaxSize()) {
        if (account is AccountState.SignedIn) {
            ActiveTaskBar(
                task = activeTask,
                onOpen = { navController.navigate(Routes.task(it)) },
                onStop = { id ->
                    scope.launch {
                        c.tasks.stop(id, StopReason.EMERGENCY_STOP)
                        c.analytics.track(AnalyticsEventName.TASK_STOPPED, mapOf("stop_reason" to "emergency_stop"))
                    }
                },
            )
        }
        NavHost(navController, startDestination = start, modifier = Modifier.weight(1f)) {
            composable(Routes.WELCOME) {
                WelcomeScreen(
                    onCreate = { navController.navigate(Routes.REGISTER) },
                    onLogin = { navController.navigate(Routes.LOGIN) },
                    onReinstall = { navController.navigate(Routes.REINSTALL) },
                )
            }
            composable(Routes.REGISTER) { RegisterScreen(onDone = ::signedIn, onBack = back, onLogin = { navController.navigate(Routes.LOGIN) }) }
            composable(Routes.LOGIN) {
                LoginScreen(
                    onSignedIn = ::signedIn,
                    onForgotPassword = { navController.navigate(Routes.FORGOT_PASSWORD) },
                    onForgotUserId = { navController.navigate(Routes.FORGOT_USER_ID) },
                    onBack = back,
                )
            }
            composable(Routes.UNLOCK) {
                UnlockScreen(onContinue = ::signedIn, onUseAnother = { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } })
            }
            composable(Routes.REINSTALL) {
                ReinstallScreen(onLogin = { navController.navigate(Routes.LOGIN) }, onForgotPassword = { navController.navigate(Routes.FORGOT_PASSWORD) }, onBack = back)
            }
            composable(Routes.FORGOT_PASSWORD) {
                ForgotPasswordScreen(onCodeSent = { navController.navigate(Routes.resetPassword(it)) }, onBack = back)
            }
            composable(Routes.RESET_PASSWORD) { e ->
                ResetPasswordScreen(
                    challengeId = e.arguments?.getString("challengeId").orEmpty(),
                    onDone = { navController.navigate(Routes.LOGIN) { popUpTo(Routes.WELCOME) } },
                    onRestart = { navController.navigate(Routes.FORGOT_PASSWORD) { popUpTo(Routes.FORGOT_PASSWORD) { inclusive = true } } },
                    onBack = back,
                )
            }
            composable(Routes.FORGOT_USER_ID) { ForgotUserIdScreen(onBack = back, onLogin = { navController.navigate(Routes.LOGIN) { popUpTo(Routes.WELCOME) } }) }

            composable(Routes.HOME) {
                HomeScreen(
                    onCategory = { navController.navigate(Routes.chat(it)) },
                    onMonth = { navController.navigate(Routes.month(it)) },
                    onSuggestions = { navController.navigate(Routes.SUGGESTIONS) },
                    onComparison = { navController.navigate(Routes.comparison(it)) },
                    onConnectHistory = { navController.navigate(Routes.HISTORY_CONNECT) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onChatHistory = { navController.navigate(Routes.CHAT_HISTORY) },
                )
            }
            composable(Routes.MONTH, listOf(navArgument("month") { type = NavType.StringType })) { e ->
                MonthDetailScreen(month = e.arguments?.getString("month").orEmpty(), onBack = back)
            }
            composable(Routes.CHAT) { e ->
                val cat = Category.fromKey(e.arguments?.getString("category").orEmpty()) ?: Category.GROCERY
                ChatScreen(category = cat, onBack = back, onComparison = { navController.navigate(Routes.comparison(it)) })
            }
            composable(Routes.CHAT_HISTORY) { ChatHistoryScreen(onBack = back, onOpen = { navController.navigate(Routes.chat(it)) }) }
            composable(Routes.COMPARISON) { e ->
                ComparisonScreen(
                    quoteId = e.arguments?.getString("quoteId").orEmpty(),
                    onBack = back,
                    onConsent = { p, q -> navController.navigate(Routes.consent(p, q)) },
                    onApprove = { navController.navigate(Routes.approve(it)) },
                    onManual = { p, q -> navController.navigate(Routes.manual(p, quoteId = q)) },
                    onRecompare = { navController.navigate(Routes.comparison(it)) { popUpTo(Routes.COMPARISON) { inclusive = true } } },
                )
            }
            composable(Routes.CONSENT, listOf(navArgument("quoteId") { type = NavType.StringType; defaultValue = "" })) { e ->
                val p = providerArg(e.arguments?.getString("provider")) ?: return@composable
                AutomationConsentScreen(
                    provider = p,
                    quoteId = e.arguments?.getString("quoteId").orEmpty(),
                    onBack = back,
                    onApprove = { navController.navigate(Routes.approve(it)) { popUpTo(Routes.CONSENT) { inclusive = true } } },
                    onManual = { navController.navigate(Routes.manual(p, quoteId = e.arguments?.getString("quoteId"))) },
                )
            }
            composable(Routes.APPROVE) { e ->
                ApproveScreen(
                    taskId = e.arguments?.getString("taskId").orEmpty(),
                    onBack = back,
                    onStarted = { navController.navigate(Routes.task(it)) { popUpTo(Routes.APPROVE) { inclusive = true } } },
                )
            }
            composable(Routes.TASK) { e ->
                TaskScreen(
                    taskId = e.arguments?.getString("taskId").orEmpty(),
                    onBack = back,
                    onReady = { navController.navigate(Routes.ready(it)) { popUpTo(Routes.TASK) { inclusive = true } } },
                    onManual = { p, q, t -> navController.navigate(Routes.manual(p, q, t)) },
                    onHome = { navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } } },
                )
            }
            composable(Routes.READY) { e ->
                CartReadyScreen(
                    taskId = e.arguments?.getString("taskId").orEmpty(),
                    onBack = back,
                    onManual = { p, t -> navController.navigate(Routes.manual(p, taskId = t)) },
                    onHome = { navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } } },
                )
            }
            composable(
                Routes.MANUAL,
                listOf(
                    navArgument("quoteId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("taskId") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e ->
                val p = providerArg(e.arguments?.getString("provider")) ?: return@composable
                ManualFallbackScreen(
                    provider = p,
                    quoteId = e.arguments?.getString("quoteId")?.ifBlank { null },
                    taskId = e.arguments?.getString("taskId")?.ifBlank { null },
                    onBack = back,
                    onHome = { navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } } },
                )
            }
            composable(Routes.SUGGESTIONS) {
                SuggestionsScreen(onBack = back, onComparison = { navController.navigate(Routes.comparison(it)) }, onConnectHistory = { navController.navigate(Routes.HISTORY_CONNECT) })
            }
            composable(Routes.HISTORY_CONNECT) {
                HistoryConnectScreen(onBack = back, onStarted = { navController.navigate(Routes.historyImport(it)) { popUpTo(Routes.HISTORY_CONNECT) { inclusive = true } } })
            }
            composable(Routes.HISTORY_IMPORT) { e ->
                ImportProgressScreen(
                    sessionId = e.arguments?.getString("sessionId").orEmpty(),
                    onBack = back,
                    onReview = { navController.navigate(Routes.historyReview(it)) { popUpTo(Routes.HISTORY_IMPORT) { inclusive = true } } },
                )
            }
            composable(Routes.HISTORY_REVIEW) { e ->
                ImportReviewScreen(sessionId = e.arguments?.getString("sessionId").orEmpty(), onBack = back, onDone = { navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } } })
            }
            composable(Routes.ORDERS) { OrdersScreen(onBack = back, onConnect = { navController.navigate(Routes.HISTORY_CONNECT) }) }

            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = back, onNavigate = { navController.navigate(it) })
            }
            composable(Routes.SET_PROVIDERS) { ProvidersScreen(onBack = back) }
            composable(Routes.SET_PRIVACY) { PrivacyScreen(onBack = back, onOrders = { navController.navigate(Routes.ORDERS) }) }
            composable(Routes.SET_SESSIONS) { SessionsScreen(onBack = back) }
            composable(Routes.SET_NOTIFICATIONS) { NotificationSettingsScreen(onBack = back) }
            composable(Routes.SET_ACTIVITY) { ActivityScreen(onBack = back) }
            composable(Routes.HELP) { HelpScreen(onBack = back) }
            composable(Routes.DEVELOPER) { DeveloperScreen(onBack = back) }
        }
    }
}
