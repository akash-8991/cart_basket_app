package com.smartcart.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartcart.app.R
import com.smartcart.app.ui.common.ErrorCopy
import com.smartcart.app.ui.common.emailProblem
import com.smartcart.app.ui.common.passwordProblem
import com.smartcart.app.ui.common.userIdProblem
import androidx.compose.ui.res.stringResource
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.app.ui.common.ScreenScaffold
import com.smartcart.data.AnalyticsEventName
import com.smartcart.data.Analytics
import com.smartcart.data.ApiResult
import com.smartcart.data.AuthRepository
import com.smartcart.designsystem.Banner
import com.smartcart.designsystem.PrimaryButton
import com.smartcart.designsystem.SecondaryButton
import com.smartcart.designsystem.Spacing
import com.smartcart.designsystem.Tone
import com.smartcart.model.AccountState
import com.smartcart.model.ApiError
import com.smartcart.model.AuthRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Shared submit state for auth forms. Passwords live only in Compose state and are never logged. */
data class SubmitState(val busy: Boolean = false, val error: ApiError? = null, val message: String? = null)

class AuthViewModel(private val auth: AuthRepository, private val analytics: Analytics) : ViewModel() {
    private val _state = MutableStateFlow(SubmitState())
    val state: StateFlow<SubmitState> = _state
    val account: StateFlow<AccountState> = auth.state

    private fun run(block: suspend () -> ApiResult<*>, onOk: (Any?) -> Unit) {
        if (_state.value.busy) return
        _state.value = SubmitState(busy = true)
        viewModelScope.launch {
            when (val r = block()) {
                is ApiResult.Success -> { _state.value = SubmitState(); onOk(r.value) }
                is ApiResult.Failure -> _state.value = SubmitState(error = r.error)
            }
        }
    }

    fun register(userId: String, email: String, password: String, onOk: () -> Unit) = run({ auth.register(userId, email.trim(), password) }) {
        analytics.track(AnalyticsEventName.AUTH_REGISTER_RESULT, mapOf("result" to "success"))
        onOk()
    }

    fun login(userId: String, password: String, remember: Boolean, onOk: () -> Unit) = run({ auth.login(userId.trim().lowercase(), password, remember) }) {
        analytics.track(AnalyticsEventName.AUTH_LOGIN_RESULT, mapOf("result" to "success"))
        onOk()
    }

    fun resume(onOk: () -> Unit) = run({ auth.resume() }) { onOk() }

    fun forgotPassword(userId: String, email: String, onOk: (String) -> Unit) = run({ auth.forgotPassword(userId.trim().lowercase(), email.trim()) }) {
        analytics.track(AnalyticsEventName.AUTH_RECOVERY_REQUESTED, mapOf("result" to "success"))
        onOk((it as com.smartcart.model.RecoveryAccepted).challengeId)
    }

    fun forgotUserId(email: String) = run({ auth.forgotUserId(email.trim()) }) {
        analytics.track(AnalyticsEventName.AUTH_RECOVERY_REQUESTED, mapOf("result" to "success"))
        _state.value = SubmitState(message = "sent")
    }

    fun reset(challengeId: String, code: String, password: String, onOk: () -> Unit) = run({ auth.resetPassword(challengeId, code.trim(), password) }) { onOk() }

    fun verifyEmail(code: String, onOk: () -> Unit) = run({ auth.verifyEmail(code.trim()) }) {
        analytics.track(AnalyticsEventName.EMAIL_VERIFICATION_RESULT, mapOf("result" to "success"))
        onOk()
    }

    fun resendVerification() = run({ auth.resendEmailVerification() }) { _state.value = SubmitState(message = "resent") }

    fun clearError() { _state.value = _state.value.copy(error = null) }
}

@Composable
private fun authVm(): AuthViewModel {
    val c = LocalAppContainer.current
    return viewModel { AuthViewModel(c.auth, c.analytics) }
}

@Composable
private fun FormColumn(padding: androidx.compose.foundation.layout.PaddingValues, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) { content() }
}

@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    supporting: String? = null,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = imeAction, autoCorrectEnabled = false),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun PasswordInput(value: String, onChange: (String) -> Unit, label: String = stringResource(R.string.auth_password), error: String? = null, supporting: String? = null, imeAction: ImeAction = ImeAction.Done) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = stringResource(if (visible) R.string.auth_hide_password else R.string.auth_show_password))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorBanner(e: ApiError?) {
    if (e != null) Banner(ErrorCopy.body(e), tone = Tone.DANGER, title = ErrorCopy.title(e))
}

@Composable
fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, text: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.size(Spacing.sm))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------- S-AUTH-01
@Composable
fun WelcomeScreen(onCreate: () -> Unit, onLogin: () -> Unit, onReinstall: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(Spacing.xl),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.padding(top = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(Icons.Outlined.ShoppingCart, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
            Text(
                stringResource(R.string.welcome_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(Spacing.sm))
            listOf(R.string.welcome_point_approval, R.string.welcome_point_payments, R.string.welcome_point_stop)
                .forEach { Text(stringResource(R.string.welcome_bullet, stringResource(it)), style = MaterialTheme.typography.bodyMedium) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PrimaryButton(stringResource(R.string.welcome_create), onCreate)
            SecondaryButton(stringResource(R.string.welcome_sign_in), onLogin)
            TextButton(onClick = onReinstall, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) { Text(stringResource(R.string.welcome_reinstalled)) }
        }
    }
}

// ---------------------------------------------------------------- S-AUTH-02
@Composable
fun RegisterScreen(onDone: () -> Unit, onBack: () -> Unit, onLogin: () -> Unit) {
    val vm = authVm()
    val s by vm.state.collectAsStateWithLifecycle()
    var userId by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var terms by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }

    val idErr = userIdProblem(if (attempted || userId.isNotEmpty()) AuthRules.userIdError(userId) else null)
    val emailErr = emailProblem(if (attempted || email.isNotEmpty()) AuthRules.emailError(email) else null)
    val pwErr = passwordProblem(if (attempted || password.isNotEmpty()) AuthRules.passwordError(password, userId) else null)
    val confirmErr = if ((attempted || confirm.isNotEmpty()) && confirm != password) stringResource(R.string.register_passwords_differ) else null
    val serverMsg = s.error?.let { ErrorCopy.body(it) }
    val serverField = s.error?.fieldErrors?.associate { it.field to serverMsg!! }.orEmpty()

    ScreenScaffold(stringResource(R.string.register_title), onBack) { padding ->
        FormColumn(padding) {
            Text(stringResource(R.string.register_intro), style = MaterialTheme.typography.bodyMedium)
            if (s.error != null && s.error!!.fieldErrors.isEmpty()) ErrorBanner(s.error)
            TextInput(userId, { userId = it.lowercase().trim(); vm.clearError() }, stringResource(R.string.auth_user_id), error = idErr ?: serverField["user_id"], supporting = stringResource(R.string.register_user_id_hint))
            TextInput(email, { email = it; vm.clearError() }, stringResource(R.string.auth_email), error = emailErr, keyboard = KeyboardType.Email)
            PasswordInput(password, { password = it; vm.clearError() }, error = pwErr ?: serverField["password"], supporting = stringResource(R.string.register_password_hint), imeAction = ImeAction.Next)
            PasswordInput(confirm, { confirm = it }, label = stringResource(R.string.register_confirm_password), error = confirmErr)
            CheckRow(terms, { terms = it }, stringResource(R.string.register_terms))
            PrimaryButton(stringResource(R.string.register_submit), loading = s.busy, onClick = {
                attempted = true
                val ok = AuthRules.userIdError(userId) == null && AuthRules.emailError(email) == null &&
                    AuthRules.passwordError(password, userId) == null && confirm == password && terms
                if (ok) vm.register(userId, email, password, onDone)
            })
            if (attempted && !terms) Text(stringResource(R.string.register_terms_required), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.register_have_account)) }
        }
    }
}

// ---------------------------------------------------------------- S-AUTH-03
@Composable
fun LoginScreen(onSignedIn: () -> Unit, onForgotPassword: () -> Unit, onForgotUserId: () -> Unit, onBack: () -> Unit) {
    val vm = authVm()
    val s by vm.state.collectAsStateWithLifecycle()
    var userId by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var remember by rememberSaveable { mutableStateOf(true) }
    ScreenScaffold(stringResource(R.string.login_title), onBack) { padding ->
        FormColumn(padding) {
            ErrorBanner(s.error)
            TextInput(userId, { userId = it; vm.clearError() }, stringResource(R.string.auth_user_id))
            PasswordInput(password, { password = it; vm.clearError() })
            CheckRow(remember, { remember = it }, stringResource(R.string.login_remember))
            PrimaryButton(
                stringResource(R.string.login_submit),
                loading = s.busy,
                enabled = userId.isNotBlank() && password.isNotEmpty(),
                onClick = { vm.login(userId, password, remember, onSignedIn) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onForgotUserId, modifier = Modifier.heightIn(min = Spacing.touchTarget)) { Text(stringResource(R.string.login_forgot_user_id)) }
                TextButton(onClick = onForgotPassword, modifier = Modifier.heightIn(min = Spacing.touchTarget)) { Text(stringResource(R.string.login_forgot_password)) }
            }
        }
    }
}

// ---------------------------------------------------------------- S-AUTH-04
@Composable
fun UnlockScreen(onContinue: () -> Unit, onUseAnother: () -> Unit) {
    val vm = authVm()
    val s by vm.state.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val userId = (account as? AccountState.Remembered)?.userId ?: ""
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(Spacing.xl), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.size(Spacing.lg))
        Text(stringResource(R.string.unlock_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.unlock_continue_as, userId), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(Spacing.lg))
        ErrorBanner(s.error)
        Spacer(Modifier.size(Spacing.md))
        PrimaryButton(stringResource(R.string.unlock_continue), loading = s.busy, onClick = { vm.resume(onContinue) })
        Spacer(Modifier.size(Spacing.sm))
        SecondaryButton(stringResource(R.string.unlock_other_account), onUseAnother)
    }
}

// ---------------------------------------------------------------- S-AUTH-05
@Composable
fun ReinstallScreen(onLogin: () -> Unit, onForgotPassword: () -> Unit, onBack: () -> Unit) {
    ScreenScaffold(stringResource(R.string.reinstall_title), onBack) { padding ->
        FormColumn(padding) {
            Text(
                stringResource(R.string.reinstall_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            Banner(stringResource(R.string.reinstall_note), tone = Tone.INFO)
            PrimaryButton(stringResource(R.string.reinstall_sign_in), onLogin)
            SecondaryButton(stringResource(R.string.reinstall_reset), onForgotPassword)
        }
    }
}

// ---------------------------------------------------------------- S-AUTH-06
@Composable
fun ForgotPasswordScreen(onCodeSent: (String) -> Unit, onBack: () -> Unit) {
    val vm = authVm()
    val s by vm.state.collectAsStateWithLifecycle()
    var userId by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    ScreenScaffold(stringResource(R.string.forgot_pw_title), onBack) { padding ->
        FormColumn(padding) {
            Text(stringResource(R.string.forgot_pw_intro), style = MaterialTheme.typography.bodyMedium)
            ErrorBanner(s.error)
            TextInput(userId, { userId = it }, stringResource(R.string.auth_user_id))
            TextInput(email, { email = it }, stringResource(R.string.auth_registered_email), keyboard = KeyboardType.Email, error = emailProblem(if (email.isNotEmpty()) AuthRules.emailError(email) else null), imeAction = ImeAction.Done)
            PrimaryButton(
                stringResource(R.string.forgot_pw_submit),
                loading = s.busy,
                enabled = userId.isNotBlank() && AuthRules.emailError(email) == null,
                onClick = { vm.forgotPassword(userId, email, onCodeSent) },
            )
        }
    }
}

// ---------------------------------------------------------------- S-AUTH-07
@Composable
fun ResetPasswordScreen(challengeId: String, onDone: () -> Unit, onRestart: () -> Unit, onBack: () -> Unit) {
    val vm = authVm()
    val s by vm.state.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var done by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(stringResource(R.string.reset_title), onBack) { padding ->
        FormColumn(padding) {
            if (done) {
                Banner(stringResource(R.string.reset_done_body), tone = Tone.SUCCESS, title = stringResource(R.string.reset_done_title))
                PrimaryButton(stringResource(R.string.reset_sign_in), onDone)
                return@FormColumn
            }
            Banner(stringResource(R.string.reset_code_sent), tone = Tone.INFO)
            ErrorBanner(s.error)
            if (s.error?.code == com.smartcart.model.ErrorCode.CHALLENGE_EXPIRED) {
                SecondaryButton(stringResource(R.string.reset_start_again), onRestart)
            }
            TextInput(code, { code = it.filter(Char::isDigit).take(8) }, stringResource(R.string.reset_code), keyboard = KeyboardType.NumberPassword)
            PasswordInput(password, { password = it }, label = stringResource(R.string.reset_new_password), error = passwordProblem(if (password.isNotEmpty()) AuthRules.passwordError(password) else null), supporting = stringResource(R.string.register_password_hint))
            PrimaryButton(
                stringResource(R.string.reset_submit),
                loading = s.busy,
                enabled = code.length >= 6 && AuthRules.passwordError(password) == null,
                onClick = { vm.reset(challengeId, code, password) { done = true } },
            )
        }
    }
}

// ---------------------------------------------------------------- S-AUTH-08
@Composable
fun ForgotUserIdScreen(onBack: () -> Unit, onLogin: () -> Unit) {
    val vm = authVm()
    val s by vm.state.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    ScreenScaffold(stringResource(R.string.forgot_id_title), onBack) { padding ->
        FormColumn(padding) {
            if (s.message != null) {
                Banner(stringResource(R.string.forgot_id_sent_body), tone = Tone.SUCCESS, title = stringResource(R.string.forgot_id_sent_title))
                PrimaryButton(stringResource(R.string.forgot_id_back), onLogin)
                return@FormColumn
            }
            Text(stringResource(R.string.forgot_id_intro), style = MaterialTheme.typography.bodyMedium)
            ErrorBanner(s.error)
            TextInput(email, { email = it }, stringResource(R.string.auth_registered_email), keyboard = KeyboardType.Email, error = emailProblem(if (email.isNotEmpty()) AuthRules.emailError(email) else null), imeAction = ImeAction.Done)
            PrimaryButton(stringResource(R.string.forgot_id_submit), loading = s.busy, enabled = AuthRules.emailError(email) == null, onClick = { vm.forgotUserId(email) })
        }
    }
}

// ---------------------------------------------------------------- S-AUTH-09 (CR-003 §2)
@Composable
fun VerifyEmailScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val vm = authVm()
    val s by vm.state.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    ScreenScaffold(stringResource(R.string.verify_title), onBack) { padding ->
        FormColumn(padding) {
            Text(stringResource(R.string.verify_body), style = MaterialTheme.typography.bodyMedium)
            if (s.message == "resent") Banner(stringResource(R.string.verify_resent), tone = Tone.SUCCESS)
            ErrorBanner(s.error)
            TextInput(code, { code = it.filter(Char::isDigit).take(8) }, stringResource(R.string.verify_code), keyboard = KeyboardType.NumberPassword, imeAction = ImeAction.Done)
            PrimaryButton(stringResource(R.string.verify_submit), loading = s.busy, enabled = code.length in 6..8, onClick = { vm.verifyEmail(code, onDone) })
            SecondaryButton(stringResource(R.string.verify_resend), { vm.resendVerification() }, enabled = !s.busy)
            SecondaryButton(stringResource(R.string.verify_later), onBack)
        }
    }
}
