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
import com.smartcart.app.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
 * User-facing copy per error code (contracts/error-codes.yaml), from string resources. Server
 * messages are never shown verbatim, so wording stays frozen and never leaks internal detail.
 */
object ErrorCopy {
    @Composable
    fun title(e: ApiError): String = stringResource(
        when (e.code) {
            ErrorCode.OFFLINE -> R.string.err_title_offline
            ErrorCode.RATE_LIMITED, ErrorCode.ACCOUNT_LOCKED -> R.string.err_title_wait
            ErrorCode.INVALID_CREDENTIALS -> R.string.err_title_sign_in_failed
            ErrorCode.SESSION_EXPIRED, ErrorCode.UNAUTHENTICATED -> R.string.err_title_sign_in_again
            ErrorCode.PROVIDER_DISABLED -> R.string.err_title_provider_paused
            ErrorCode.CONSENT_REQUIRED -> R.string.err_title_permission
            ErrorCode.AI_UNAVAILABLE -> R.string.err_title_ai_limited
            ErrorCode.POLICY_BLOCKED -> R.string.err_title_policy
            ErrorCode.TASK_EXPIRED, ErrorCode.QUOTE_EXPIRED, ErrorCode.CHALLENGE_EXPIRED -> R.string.err_title_expired
            else -> R.string.err_title_generic
        },
    )

    @Composable
    fun body(e: ApiError): String = when (e.code) {
        ErrorCode.RATE_LIMITED -> waitText(e, stringResource(R.string.err_body_rate_limited))
        ErrorCode.ACCOUNT_LOCKED -> waitText(e, stringResource(R.string.err_body_locked))
        else -> stringResource(
            when (e.code) {
                ErrorCode.OFFLINE -> R.string.err_body_offline
                ErrorCode.INVALID_CREDENTIALS -> R.string.err_body_invalid_credentials
                ErrorCode.SESSION_EXPIRED, ErrorCode.UNAUTHENTICATED -> R.string.err_body_session
                ErrorCode.USER_ID_UNAVAILABLE -> R.string.err_body_user_id_taken
                ErrorCode.WEAK_PASSWORD -> R.string.err_body_weak_password
                ErrorCode.CHALLENGE_EXPIRED -> R.string.err_body_challenge_expired
                ErrorCode.PROVIDER_DISABLED -> R.string.err_body_provider_disabled
                ErrorCode.CONSENT_REQUIRED -> R.string.err_body_consent
                ErrorCode.AI_UNAVAILABLE -> R.string.err_body_ai
                ErrorCode.POLICY_BLOCKED -> R.string.err_body_policy
                ErrorCode.TASK_EXPIRED -> R.string.err_body_task_expired
                ErrorCode.QUOTE_EXPIRED -> R.string.err_body_quote_expired
                ErrorCode.TASK_STATE_CONFLICT, ErrorCode.CONFLICT -> R.string.err_body_conflict
                ErrorCode.VALIDATION_FAILED -> R.string.err_body_validation
                else -> R.string.err_body_generic
            },
        )
    }

    @Composable
    private fun waitText(e: ApiError, lead: String): String {
        val s = e.retryAfterSeconds ?: return stringResource(R.string.err_body_try_later, lead)
        val mins = (s + 59) / 60
        return pluralStringResource(R.plurals.err_body_try_in_minutes, mins, lead, mins)
    }
}

@Composable
fun LoadContent(state: Load<*>, onRetry: () -> Unit, loadingLabel: String = stringResource(R.string.common_loading), content: @Composable () -> Unit) {
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
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
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
