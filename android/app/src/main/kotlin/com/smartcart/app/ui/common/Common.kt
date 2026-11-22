package com.smartcart.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.smartcart.app.AppContainer
import com.smartcart.data.ApiResult
import com.smartcart.designsystem.ErrorState
import com.smartcart.designsystem.LoadingState
import com.smartcart.model.ApiError
import com.smartcart.model.ErrorCode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val LocalAppContainer = compositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** Generic load state used by every screen (catalogue L / S / X / O). */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val error: ApiError) : Load<Nothing>
}

fun <T> ApiResult<T>.toLoad(): Load<T> = when (this) {
    is ApiResult.Success -> Load.Ready(value)
    is ApiResult.Failure -> Load.Failed(error)
}

/**
 * User-facing copy per error code (contracts/error-codes.yaml). Server messages are not shown
 * verbatim so wording stays frozen and never leaks internal detail.
 */
object ErrorCopy {
    fun title(e: ApiError): String = when (e.code) {
        ErrorCode.OFFLINE -> "You're offline"
        ErrorCode.RATE_LIMITED, ErrorCode.ACCOUNT_LOCKED -> "Please wait a moment"
        ErrorCode.INVALID_CREDENTIALS -> "Couldn't sign you in"
        ErrorCode.SESSION_EXPIRED, ErrorCode.UNAUTHENTICATED -> "Please sign in again"
        ErrorCode.PROVIDER_DISABLED -> "This app is paused"
        ErrorCode.CONSENT_REQUIRED -> "Permission needed"
        ErrorCode.AI_UNAVAILABLE -> "Assistant is limited right now"
        ErrorCode.POLICY_BLOCKED -> "Blocked for your safety"
        ErrorCode.TASK_EXPIRED, ErrorCode.QUOTE_EXPIRED, ErrorCode.CHALLENGE_EXPIRED -> "This has expired"
        else -> "Something went wrong"
    }

    fun body(e: ApiError): String = when (e.code) {
        ErrorCode.OFFLINE -> "Check your connection and try again."
        ErrorCode.RATE_LIMITED -> waitText(e, "You're going a bit fast.")
        ErrorCode.ACCOUNT_LOCKED -> waitText(e, "Too many attempts.")
        ErrorCode.INVALID_CREDENTIALS -> "User ID or password is incorrect."
        ErrorCode.SESSION_EXPIRED, ErrorCode.UNAUTHENTICATED -> "For your security, your session ended."
        ErrorCode.USER_ID_UNAVAILABLE -> "That user ID is taken. Try another."
        ErrorCode.WEAK_PASSWORD -> "Choose a longer, less common password."
        ErrorCode.CHALLENGE_EXPIRED -> "That code has expired or was already used. Start again."
        ErrorCode.PROVIDER_DISABLED -> "Automation for this app is temporarily unavailable. You can continue manually."
        ErrorCode.CONSENT_REQUIRED -> "Allow automation or history access for this app first."
        ErrorCode.AI_UNAVAILABLE -> "You can still type items and quantities, one per line."
        ErrorCode.POLICY_BLOCKED -> e.message
        ErrorCode.TASK_EXPIRED -> "This request expired. Start again."
        ErrorCode.QUOTE_EXPIRED -> "Prices need to be checked again."
        ErrorCode.TASK_STATE_CONFLICT, ErrorCode.CONFLICT -> "This changed in the meantime. Refreshing."
        ErrorCode.VALIDATION_FAILED -> e.message
        else -> "Please try again in a moment."
    }

    private fun waitText(e: ApiError, lead: String): String {
        val s = e.retryAfterSeconds ?: return "$lead Try again later."
        val mins = (s + 59) / 60
        return "$lead Try again in $mins minute${if (mins == 1) "" else "s"}."
    }
}

@Composable
fun LoadContent(state: Load<*>, onRetry: () -> Unit, loadingLabel: String = "Loading", content: @Composable () -> Unit) {
    when (state) {
        Load.Loading -> LoadingState(label = loadingLabel)
        is Load.Failed -> ErrorState(ErrorCopy.title(state.error), ErrorCopy.body(state.error), onRetry = onRetry, offline = state.error.code == ErrorCode.OFFLINE)
        is Load.Ready<*> -> content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = { actions() },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = bottomBar,
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) { content(padding) }
        }
    }
}

object Format {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dateTime = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.forLanguageTag("en-IN"))
    private val date = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("en-IN"))
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.forLanguageTag("en-IN"))
    private val month = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("en-IN"))
    private val monthShort = DateTimeFormatter.ofPattern("MMM", Locale.forLanguageTag("en-IN"))

    fun dateTime(iso: String?): String = iso?.let { runCatching { dateTime.format(Instant.parse(it).atZone(zone)) }.getOrNull() } ?: "—"
    fun date(iso: String?): String = iso?.let { runCatching { date.format(Instant.parse(it).atZone(zone)) }.getOrNull() } ?: "—"
    fun time(iso: String?): String = iso?.let { runCatching { time.format(Instant.parse(it).atZone(zone)) }.getOrNull() } ?: "—"
    fun month(ym: String): String = runCatching { month.format(YearMonth.parse(ym)) }.getOrDefault(ym)
    fun monthShort(ym: String): String = runCatching { monthShort.format(YearMonth.parse(ym)) }.getOrDefault(ym)

    fun pct(v: Double?): String? = v?.let { (if (it > 0) "+" else "") + String.format(Locale.US, "%.1f%%", it) }
    fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v)
}
