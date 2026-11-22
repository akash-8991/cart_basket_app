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
import com.smartcart.app.ui.common.ErrorCopy
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
        _state.value = SubmitState(message = (it as com.smartcart.model.RecoveryAccepted).message)
    }

    fun reset(challengeId: String, code: String, password: String, onOk: () -> Unit) = run({ auth.resetPassword(challengeId, code.trim(), password) }) { onOk() }

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
fun PasswordInput(value: String, onChange: (String) -> Unit, label: String = "Password", error: String? = null, supporting: String? = null, imeAction: ImeAction = ImeAction.Done) {
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
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (visible) "Hide password" else "Show password")
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
            Text("SmartCart", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
            Text(
                "Tell me what you need. I compare the full cart across your apps, prepare it with your approval, and you pay in the app yourself.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(Spacing.sm))
            listOf(
                "Nothing happens in your apps without your approval",
                "Payments, OTPs and passwords always stay with you",
                "Stop any task at any time",
            ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PrimaryButton("Create account", onCreate)
            SecondaryButton("Sign in", onLogin)
            TextButton(onClick = onReinstall, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) { Text("Reinstalled the app?") }
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

    val idErr = if (attempted || userId.isNotEmpty()) AuthRules.userIdError(userId) else null
    val emailErr = if (attempted || email.isNotEmpty()) AuthRules.emailError(email) else null
    val pwErr = if (attempted || password.isNotEmpty()) AuthRules.passwordError(password, userId) else null
    val confirmErr = if ((attempted || confirm.isNotEmpty()) && confirm != password) "Passwords don't match" else null
    val serverField = s.error?.fieldErrors?.associate { it.field to ErrorCopy.body(s.error!!) }.orEmpty()

    ScreenScaffold("Create account", onBack) { padding ->
        FormColumn(padding) {
            Text("Your user ID and password sign you in. Your email is only used to recover your account.", style = MaterialTheme.typography.bodyMedium)
            if (s.error != null && s.error!!.fieldErrors.isEmpty()) ErrorBanner(s.error)
            TextInput(userId, { userId = it.lowercase().trim(); vm.clearError() }, "User ID", error = idErr ?: serverField["user_id"], supporting = "4–30 characters: a–z, 0–9, . _ -")
            TextInput(email, { email = it; vm.clearError() }, "Email", error = emailErr, keyboard = KeyboardType.Email)
            PasswordInput(password, { password = it; vm.clearError() }, error = pwErr ?: serverField["password"], supporting = "At least 10 characters", imeAction = ImeAction.Next)
            PasswordInput(confirm, { confirm = it }, label = "Confirm password", error = confirmErr)
            CheckRow(terms, { terms = it }, "I agree to the Terms (v2026-11) and have read the Privacy notice (v2026-11)")
            PrimaryButton("Create account", loading = s.busy, onClick = {
                attempted = true
                val ok = AuthRules.userIdError(userId) == null && AuthRules.emailError(email) == null &&
                    AuthRules.passwordError(password, userId) == null && confirm == password && terms
                if (ok) vm.register(userId, email, password, onDone)
            })
            if (attempted && !terms) Text("Accept the terms to continue", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("Already have an account? Sign in") }
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
    ScreenScaffold("Sign in", onBack) { padding ->
        FormColumn(padding) {
            ErrorBanner(s.error)
            TextInput(userId, { userId = it; vm.clearError() }, "User ID")
            PasswordInput(password, { password = it; vm.clearError() })
            CheckRow(remember, { remember = it }, "Keep me signed in on this device")
            PrimaryButton(
                "Sign in",
                loading = s.busy,
                enabled = userId.isNotBlank() && password.isNotEmpty(),
                onClick = { vm.login(userId, password, remember, onSignedIn) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onForgotUserId, modifier = Modifier.heightIn(min = Spacing.touchTarget)) { Text("Forgot user ID?") }
                TextButton(onClick = onForgotPassword, modifier = Modifier.heightIn(min = Spacing.touchTarget)) { Text("Forgot password?") }
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
        Text("Welcome back", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text("Continue as $userId", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(Spacing.lg))
        ErrorBanner(s.error)
        Spacer(Modifier.size(Spacing.md))
        PrimaryButton("Continue", loading = s.busy, onClick = { vm.resume(onContinue) })
        Spacer(Modifier.size(Spacing.sm))
        SecondaryButton("Use another account", onUseAnother)
    }
}

// ---------------------------------------------------------------- S-AUTH-05
@Composable
fun ReinstallScreen(onLogin: () -> Unit, onForgotPassword: () -> Unit, onBack: () -> Unit) {
    ScreenScaffold("Reinstalled the app?", onBack) { padding ->
        FormColumn(padding) {
            Text(
                "For your security, sign-in sessions are tied to this installation and are removed when the app is uninstalled. Sign in again with your user ID and password.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Banner("SmartCart never stores your password, so it cannot be recovered or shown to you. If you've forgotten it, reset it using your registered email.", tone = Tone.INFO)
            PrimaryButton("Sign in", onLogin)
            SecondaryButton("Reset password", onForgotPassword)
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
    ScreenScaffold("Reset password", onBack) { padding ->
        FormColumn(padding) {
            Text("Enter your user ID and the email registered to it. If they match, we'll email you a reset code.", style = MaterialTheme.typography.bodyMedium)
            ErrorBanner(s.error)
            TextInput(userId, { userId = it }, "User ID")
            TextInput(email, { email = it }, "Registered email", keyboard = KeyboardType.Email, error = if (email.isNotEmpty()) AuthRules.emailError(email) else null, imeAction = ImeAction.Done)
            PrimaryButton(
                "Send reset code",
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
    ScreenScaffold("Choose a new password", onBack) { padding ->
        FormColumn(padding) {
            if (done) {
                Banner("Password changed. You've been signed out on all other devices.", tone = Tone.SUCCESS, title = "All set")
                PrimaryButton("Sign in", onDone)
                return@FormColumn
            }
            Banner("If the details matched an account, a code was sent to the registered email. It expires in 15 minutes.", tone = Tone.INFO)
            ErrorBanner(s.error)
            if (s.error?.code == com.smartcart.model.ErrorCode.CHALLENGE_EXPIRED) {
                SecondaryButton("Start again", onRestart)
            }
            TextInput(code, { code = it.filter(Char::isDigit).take(8) }, "Code from email", keyboard = KeyboardType.NumberPassword)
            PasswordInput(password, { password = it }, label = "New password", error = if (password.isNotEmpty()) AuthRules.passwordError(password) else null, supporting = "At least 10 characters")
            PrimaryButton(
                "Change password",
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
    ScreenScaffold("Forgot user ID", onBack) { padding ->
        FormColumn(padding) {
            if (s.message != null) {
                Banner(s.message!!, tone = Tone.SUCCESS, title = "Check your email")
                PrimaryButton("Back to sign in", onLogin)
                return@FormColumn
            }
            Text("Enter your registered email. For your security, your user ID is only ever sent to that email, never shown here.", style = MaterialTheme.typography.bodyMedium)
            ErrorBanner(s.error)
            TextInput(email, { email = it }, "Registered email", keyboard = KeyboardType.Email, error = if (email.isNotEmpty()) AuthRules.emailError(email) else null, imeAction = ImeAction.Done)
            PrimaryButton("Email my user ID", loading = s.busy, enabled = AuthRules.emailError(email) == null, onClick = { vm.forgotUserId(email) })
        }
    }
}
