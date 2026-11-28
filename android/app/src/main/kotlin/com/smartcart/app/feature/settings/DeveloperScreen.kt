package com.smartcart.app.feature.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.app.ui.common.ScreenScaffold
import com.smartcart.data.FlagKeys
import com.smartcart.data.mock.ChatScenario
import com.smartcart.data.mock.ComparisonScenario
import com.smartcart.data.mock.DashboardScenario
import com.smartcart.data.mock.HandoffScenario
import com.smartcart.data.mock.ImportScenario
import com.smartcart.data.mock.ListScenario
import com.smartcart.data.mock.LoginScenario
import com.smartcart.data.mock.MockConfig
import com.smartcart.data.mock.RecoveryScenario
import com.smartcart.data.mock.RegisterScenario
import com.smartcart.data.mock.ResetScenario
import com.smartcart.data.mock.SessionRestoreScenario
import com.smartcart.data.mock.TaskScenario
import com.smartcart.designsystem.Banner
import com.smartcart.designsystem.SectionHeader
import com.smartcart.designsystem.Spacing
import com.smartcart.designsystem.Tone

/** Debug-only switcher that selects which contract fixture each fake repository returns. */
@Composable
fun DeveloperScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val cfg by c.mock.config.collectAsStateWithLifecycle()
    val flags by c.flags.values.collectAsStateWithLifecycle()
    val events by c.analytics.events.collectAsStateWithLifecycle()
    val testStoreOn = c.testStore?.enabled?.collectAsStateWithLifecycle()

    @Composable
    fun <E : Enum<E>> Choice(label: String, values: Array<E>, current: E, set: (MockConfig, E) -> MockConfig) {
        Column(Modifier.padding(vertical = Spacing.xs)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                values.forEach { v -> FilterChip(selected = v == current, onClick = { c.mock.update { set(it, v) } }, label = { Text(v.name.lowercase().replace('_', ' ')) }) }
            }
        }
    }

    ScreenScaffold("Mock scenarios", onBack) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl)) {
            item { Banner("Debug builds only. Each switch selects a contract fixture so every screen state can be reviewed.", tone = Tone.INFO) }
            item { SectionHeader("Network") }
            item { SwitchRow("Offline", cfg.offline, { on -> c.mock.update { it.copy(offline = on) } }) }
            item { SwitchRow("Slow network (2.5 s)", cfg.latencyMs > 1000, { on -> c.mock.update { it.copy(latencyMs = if (on) 2500 else 700) } }) }
            testStoreOn?.let { on ->
                item { SectionHeader("On-device automation (Phase 4.2–4.3)") }
                item { SwitchRow("Blinkit runs in SmartCart Test Store", on.value, { v -> c.testStore?.enabled?.value = v }) }
                item { Text("Blinkit's prices are read and its carts built in the Test Store. Needs the Test Store app installed and SmartCart turned on under Settings → Accessibility. Other providers keep their fixture figures and simulated tasks.", style = MaterialTheme.typography.bodySmall) }
            }
            item { SectionHeader("Scenarios") }
            item { Choice("Login", LoginScenario.entries.toTypedArray(), cfg.login) { m, v -> m.copy(login = v) } }
            item { Choice("Register", RegisterScenario.entries.toTypedArray(), cfg.register) { m, v -> m.copy(register = v) } }
            item { Choice("Remembered session", SessionRestoreScenario.entries.toTypedArray(), cfg.sessionRestore) { m, v -> m.copy(sessionRestore = v) } }
            item { Choice("Recovery", RecoveryScenario.entries.toTypedArray(), cfg.recovery) { m, v -> m.copy(recovery = v) } }
            item { Choice("Reset password", ResetScenario.entries.toTypedArray(), cfg.resetPassword) { m, v -> m.copy(resetPassword = v) } }
            item { Choice("Dashboard", DashboardScenario.entries.toTypedArray(), cfg.dashboard) { m, v -> m.copy(dashboard = v) } }
            item { Choice("Chat", ChatScenario.entries.toTypedArray(), cfg.chat) { m, v -> m.copy(chat = v) } }
            item { Choice("Comparison", ComparisonScenario.entries.toTypedArray(), cfg.comparison) { m, v -> m.copy(comparison = v) } }
            item { Choice("Cart task outcome", TaskScenario.entries.toTypedArray(), cfg.task) { m, v -> m.copy(task = v) } }
            item { Choice("Cart handoff", HandoffScenario.entries.toTypedArray(), cfg.handoff) { m, v -> m.copy(handoff = v) } }
            item { Choice("History import", ImportScenario.entries.toTypedArray(), cfg.importFlow) { m, v -> m.copy(importFlow = v) } }
            item { Choice("Order list", ListScenario.entries.toTypedArray(), cfg.history) { m, v -> m.copy(history = v) } }
            item { Choice("Suggestions", ListScenario.entries.toTypedArray(), cfg.suggestions) { m, v -> m.copy(suggestions = v) } }
            item { Choice("Activity", ListScenario.entries.toTypedArray(), cfg.activity) { m, v -> m.copy(activity = v) } }
            item { SectionHeader("Feature flags") }
            items(flags.keys.sorted(), key = { it }) { k -> SwitchRow(k, flags[k] == true, { c.flags.set(k, it) }) }
            item { SectionHeader("Analytics events (local collector)") }
            item { Text("${events.size} events · no PII is collected", style = MaterialTheme.typography.bodySmall) }
            items(events.take(50), key = { it.at + it.event + it.props.hashCode() }) { e ->
                Column(Modifier.padding(vertical = Spacing.xs)) {
                    Text(e.event, style = MaterialTheme.typography.labelLarge)
                    Text(e.props.entries.joinToString { "${it.key}=${it.value}" }, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}
