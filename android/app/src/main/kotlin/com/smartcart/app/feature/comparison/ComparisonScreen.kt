package com.smartcart.app.feature.comparison

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartcart.app.AppContainer
import com.smartcart.app.feature.home.confidenceTone
import com.smartcart.app.ui.common.ErrorCopy
import com.smartcart.app.ui.common.Format
import com.smartcart.app.ui.common.Load
import com.smartcart.app.ui.common.LoadContent
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.app.ui.common.ScreenScaffold
import com.smartcart.app.ui.common.toLoad
import com.smartcart.data.AnalyticsEventName
import com.smartcart.data.ApiResult
import com.smartcart.designsystem.AppCard
import com.smartcart.designsystem.Banner
import com.smartcart.designsystem.KeyValueRow
import com.smartcart.designsystem.Pill
import com.smartcart.designsystem.PrimaryButton
import com.smartcart.designsystem.ProgressRow
import com.smartcart.designsystem.ProviderMonogram
import com.smartcart.designsystem.SecondaryButton
import com.smartcart.designsystem.SectionHeader
import com.smartcart.designsystem.SmartCartTheme
import com.smartcart.designsystem.Spacing
import com.smartcart.designsystem.Tone
import com.smartcart.model.ApiError
import com.smartcart.model.Availability
import com.smartcart.model.ComparisonQuote
import com.smartcart.model.ComparisonStatus
import com.smartcart.model.ErrorCode
import com.smartcart.model.LineMatch
import com.smartcart.model.ProviderKey
import com.smartcart.model.ProviderResult
import com.smartcart.model.ProviderStatus
import com.smartcart.model.QuoteRequest
import com.smartcart.model.RecommendationLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ComparisonNav {
    data class Consent(val provider: ProviderKey, val quoteId: String) : ComparisonNav
    data class Approve(val taskId: String) : ComparisonNav
    data class Manual(val provider: ProviderKey, val quoteId: String) : ComparisonNav
    data class Recompare(val quoteId: String) : ComparisonNav
}

data class ComparisonUi(
    val quote: Load<ComparisonQuote> = Load.Loading,
    val pendingSwitch: ProviderKey? = null,
    val busy: ProviderKey? = null,
    val actionError: ApiError? = null,
    /** Provider the user last tried to use; offered for manual fallback when automation fails. */
    val lastChosen: ProviderKey? = null,
    val nav: ComparisonNav? = null,
)

class ComparisonViewModel(private val c: AppContainer, private val quoteId: String) : ViewModel() {
    private val _state = MutableStateFlow(ComparisonUi())
    val state: StateFlow<ComparisonUi> = _state
    private var viewedLogged = false

    init { load() }

    fun load() {
        viewModelScope.launch {
            c.comparisons.observe(quoteId).collect { r ->
                _state.update { it.copy(quote = r.toLoad()) }
                val q = (r as? ApiResult.Success)?.value
                if (q != null && q.status != ComparisonStatus.IN_PROGRESS && !viewedLogged) {
                    viewedLogged = true
                    c.analytics.track(
                        AnalyticsEventName.COMPARISON_VIEWED,
                        mapOf("category" to q.category.key, "comparison_status" to q.status.name.lowercase(), "providers_checked" to q.coverage.checked.size, "recommended_provider" to q.recommendation.provider?.key),
                    )
                }
            }
        }
    }

    fun choose(p: ProviderKey) {
        val q = (state.value.quote as? Load.Ready)?.value ?: return
        c.analytics.track(AnalyticsEventName.COMPARISON_PROVIDER_CHOSEN, mapOf("provider" to p.key, "recommended_provider" to q.recommendation.provider?.key))
        if (q.needsSwitchConfirmation(p)) _state.update { it.copy(pendingSwitch = p) } else proceed(p, acknowledged = false)
    }

    fun dismissSwitch() = _state.update { it.copy(pendingSwitch = null) }

    fun confirmSwitch() {
        val p = state.value.pendingSwitch ?: return
        c.analytics.track(AnalyticsEventName.COMPARISON_SWITCH_CONFIRMED, mapOf("provider" to p.key))
        _state.update { it.copy(pendingSwitch = null) }
        proceed(p, acknowledged = true)
    }

    private fun proceed(p: ProviderKey, acknowledged: Boolean) {
        _state.update { it.copy(busy = p, actionError = null, lastChosen = p) }
        viewModelScope.launch {
            val confirm = c.comparisons.confirm(quoteId, p, acknowledged)
            if (confirm is ApiResult.Failure) { _state.update { it.copy(busy = null, actionError = confirm.error) }; return@launch }
            val provider = c.providers.providers.value.firstOrNull { it.key == p }
            val nav = when {
                provider == null || !provider.automationAvailable -> ComparisonNav.Manual(p, quoteId)
                !provider.consent.automation -> ComparisonNav.Consent(p, quoteId)
                else -> when (val t = c.tasks.create(quoteId, p)) {
                    is ApiResult.Success -> ComparisonNav.Approve(t.value.taskId)
                    is ApiResult.Failure -> {
                        if (t.error.code == ErrorCode.CONSENT_REQUIRED) ComparisonNav.Consent(p, quoteId)
                        else { _state.update { it.copy(busy = null, actionError = t.error) }; return@launch }
                    }
                }
            }
            _state.update { it.copy(busy = null, nav = nav) }
        }
    }

    fun recompare() {
        val q = (state.value.quote as? Load.Ready)?.value ?: return
        viewModelScope.launch {
            val req = QuoteRequest(q.category, emptyList(), null, q.providerResults.map { it.provider }, q.selectedProvider, q.objective)
            when (val r = c.comparisons.create(req)) {
                is ApiResult.Success -> _state.update { it.copy(nav = ComparisonNav.Recompare(r.value)) }
                is ApiResult.Failure -> _state.update { it.copy(actionError = r.error) }
            }
        }
    }

    fun navHandled() = _state.update { it.copy(nav = null) }
}

// ---------------------------------------------------------------- S-CMP-01
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparisonScreen(
    quoteId: String,
    onBack: () -> Unit,
    onConsent: (ProviderKey, String) -> Unit,
    onApprove: (String) -> Unit,
    onManual: (ProviderKey, String) -> Unit,
    onRecompare: (String) -> Unit,
) {
    val c = LocalAppContainer.current
    val vm = viewModel(key = "cmp-$quoteId") { ComparisonViewModel(c, quoteId) }
    val s by vm.state.collectAsStateWithLifecycle()
    var breakdown by remember { mutableStateOf<ProviderResult?>(null) }

    LaunchedEffect(s.nav) {
        when (val n = s.nav) {
            is ComparisonNav.Approve -> onApprove(n.taskId)
            is ComparisonNav.Consent -> onConsent(n.provider, n.quoteId)
            is ComparisonNav.Manual -> onManual(n.provider, n.quoteId)
            is ComparisonNav.Recompare -> onRecompare(n.quoteId)
            null -> return@LaunchedEffect
        }
        vm.navHandled()
    }

    ScreenScaffold("Compare prices", onBack) { padding ->
        LoadContent(s.quote, onRetry = vm::load, loadingLabel = "Checking prices") {
            val q = (s.quote as Load.Ready).value
            val recommended = q.recommendation.provider?.takeIf { q.badgeLabel != RecommendationLabel.NONE }
            LazyColumn(
                contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.navigationBarsPadding(),
            ) {
                item { StatusHeader(q, onRecompare = vm::recompare) }
                s.actionError?.let { e ->
                    item {
                        Banner(
                            ErrorCopy.body(e), tone = Tone.DANGER, title = ErrorCopy.title(e),
                            actionLabel = if (e.code in setOf(ErrorCode.PROVIDER_DISABLED, ErrorCode.POLICY_BLOCKED) && s.lastChosen != null) "Continue manually" else null,
                            onAction = { s.lastChosen?.let { onManual(it, q.quoteId) } },
                        )
                    }
                }
                if (recommended != null && q.status != ComparisonStatus.IN_PROGRESS) {
                    item { RecommendationCard(q, recommended, busy = s.busy == recommended, onUse = { vm.choose(recommended) }) }
                }
                item { SectionHeader(if (q.providerResults.size > 1) "All apps checked" else "App checked") }
                items(q.rankedResults, key = { it.provider.key }) { r ->
                    ProviderResultCard(
                        r = r,
                        quote = q,
                        isRecommended = r.provider == recommended,
                        busy = s.busy == r.provider,
                        onChoose = { vm.choose(r.provider) },
                        onBreakdown = { breakdown = r },
                        onManual = { onManual(r.provider, q.quoteId) },
                    )
                }
                if (q.status != ComparisonStatus.IN_PROGRESS) item { Footnotes(q) }
            }
        }
    }

    s.pendingSwitch?.let { p ->
        val q = (s.quote as? Load.Ready)?.value
        val from = q?.selectedProvider
        AlertDialog(
            onDismissRequest = vm::dismissSwitch,
            title = { Text("Switch to ${p.displayName}?") },
            text = {
                Text(
                    "You started with ${from?.displayName}. Your cart will be prepared in ${p.displayName} instead" +
                        (q?.recommendation?.savingsVsSelected?.takeIf { p == q.recommendation.provider }?.let { ", which is estimated to be ${it.format()} cheaper" } ?: "") +
                        ". Nothing changes in ${from?.displayName}.",
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmSwitch) { Text("Use ${p.displayName}") } },
            dismissButton = { TextButton(onClick = vm::dismissSwitch) { Text("Keep ${from?.displayName}") } },
        )
    }

    breakdown?.let { r ->
        ModalBottomSheet(onDismissRequest = { breakdown = null }) {
            CostBreakdown(r, Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xxl))
        }
    }
}

@Composable
private fun StatusHeader(q: ComparisonQuote, onRecompare: () -> Unit) {
    val failed = q.coverage.failed.joinToString { it.displayName }
    when (q.status) {
        ComparisonStatus.IN_PROGRESS -> {
            val done = q.providerResults.count { it.availability != Availability.NOT_CHECKED }
            ProgressRow("Checking ${q.providerResults.size} apps… ($done done)", progress = if (q.providerResults.isEmpty()) null else done.toFloat() / q.providerResults.size)
        }
        ComparisonStatus.COMPLETE -> Unit
        ComparisonStatus.PARTIAL_COVERAGE -> Banner(
            "Some apps couldn't be checked${if (failed.isNotEmpty()) ": $failed" else ""}. This is the lowest price I could see, not a guaranteed cheapest.",
            tone = Tone.WARNING, title = "Partial comparison",
        )
        ComparisonStatus.STALE -> Banner("Prices were checked at ${Format.dateTime(q.createdAt)} and may have changed.", tone = Tone.WARNING, title = "Prices may be out of date", actionLabel = "Check prices again", onAction = onRecompare)
        ComparisonStatus.INCOMPARABLE -> Banner(q.recommendation.reason, tone = Tone.INFO, title = "Not a like-for-like comparison")
        ComparisonStatus.UNAVAILABLE -> Banner(q.recommendation.reason, tone = Tone.WARNING, title = "Not available right now")
        ComparisonStatus.PRICE_CHANGED -> Banner(
            "The estimated total changed from ${q.previousTotalEstimate?.format()} to ${q.providerResults.firstOrNull { it.provider == q.selectedProvider }?.totalEstimate?.format()}. Please check before continuing.",
            tone = Tone.WARNING, title = "Price changed",
        )
        ComparisonStatus.MANUAL_ONLY -> Banner(q.recommendation.reason, tone = Tone.INFO, title = "Automatic price check unavailable")
    }
}

@Composable
private fun RecommendationCard(q: ComparisonQuote, p: ProviderKey, busy: Boolean, onUse: () -> Unit) {
    val r = q.providerResults.first { it.provider == p }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(q.badgeLabel.badge, tone = if (q.badgeLabel == RecommendationLabel.CHEAPEST) Tone.SUCCESS else Tone.INFO)
            Spacer(Modifier.width(Spacing.sm))
            Pill("${q.confidence.name.lowercase().replaceFirstChar { it.uppercase() }} confidence", tone = confidenceTone(q.confidence))
        }
        Spacer(Modifier.size(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderMonogram(p.displayName)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(p.displayName, style = MaterialTheme.typography.titleMedium)
                r.etaMinutes?.let { Text("About $it min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(r.totalEstimate?.format() ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.size(Spacing.sm))
        val savings = q.recommendation.savingsVsSelected
        Text(
            if (savings != null && savings.amountMinor > 0) {
                "Recommended because the estimated total is ${savings.format()} lower than ${q.selectedProvider?.displayName}, based on prices checked at ${Format.time(r.capturedAt ?: q.createdAt)}. Final price may change in ${p.displayName}."
            } else {
                "${q.recommendation.reason} Prices checked at ${Format.time(r.capturedAt ?: q.createdAt)}. Final price may change in ${p.displayName}."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(Spacing.md))
        PrimaryButton("Use ${p.displayName}", onClick = onUse, loading = busy)
    }
}

@Composable
private fun ProviderResultCard(
    r: ProviderResult,
    quote: ComparisonQuote,
    isRecommended: Boolean,
    busy: Boolean,
    onChoose: () -> Unit,
    onBreakdown: () -> Unit,
    onManual: () -> Unit,
) {
    val c = LocalAppContainer.current
    val providers by c.providers.providers.collectAsStateWithLifecycle()
    val providerStatus = providers.firstOrNull { it.key == r.provider }?.status
    val desc = "${r.provider.displayName}, ${r.availability.label}" + (r.totalEstimate?.let { ", estimated total ${it.format()}" } ?: "") + (r.etaMinutes?.let { ", about $it minutes" } ?: "")
    AppCard(modifier = Modifier.semantics { contentDescription = desc }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderMonogram(r.provider.displayName, size = 36)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.provider.displayName, style = MaterialTheme.typography.titleSmall)
                    if (r.provider == quote.selectedProvider) { Spacer(Modifier.width(Spacing.xs)); Pill("Your pick") }
                    if (isRecommended) { Spacer(Modifier.width(Spacing.xs)); Pill("Recommended", tone = Tone.SUCCESS) }
                }
                val meta = listOfNotNull(r.etaMinutes?.let { "~$it min" }, r.availability.takeIf { it != Availability.AVAILABLE }?.label).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(r.totalEstimate?.format() ?: "—", style = MaterialTheme.typography.titleMedium)
        }
        r.lines.filter { it.match != LineMatch.EXACT }.forEach { l ->
            Text("• ${l.requestedName}: ${l.match.label}${l.matchedSize?.let { s -> " ($s)" } ?: ""}${l.note?.let { n -> " — $n" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = SmartCartTheme.semantic.warning)
        }
        r.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (r.unavailableFields.isNotEmpty()) {
            Text("Not visible: ${r.unavailableFields.joinToString { it.replace('_', ' ') }}", style = MaterialTheme.typography.bodySmall, color = SmartCartTheme.semantic.warning)
        }
        if (quote.status != ComparisonStatus.IN_PROGRESS) {
            Row {
                if (r.totalEstimate != null) TextButton(onClick = onBreakdown) { Text("Cost breakdown") }
                Spacer(Modifier.weight(1f))
                when {
                    providerStatus != ProviderStatus.ENABLED || quote.status == ComparisonStatus.MANUAL_ONLY ->
                        TextButton(onClick = onManual) { Text("Open manually") }
                    r.availability in setOf(Availability.AVAILABLE, Availability.PARTIAL) && !isRecommended ->
                        TextButton(onClick = onChoose, enabled = !busy) { Text(if (busy) "Please wait…" else "Choose this app") }
                    isRecommended -> Unit
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun CostBreakdown(r: ProviderResult, modifier: Modifier) {
    Column(modifier) {
        Text("${r.provider.displayName} estimate", style = MaterialTheme.typography.titleLarge)
        Text("Checked at ${Format.dateTime(r.capturedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(Spacing.md))
        r.lines.forEach { l ->
            KeyValueRow("${l.matchedName ?: l.requestedName}${l.matchedSize?.let { " $it" } ?: ""} × ${l.quantity}", l.lineTotal?.format() ?: l.match.label)
        }
        HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
        r.components.rows().forEach { row ->
            val amount = row.amount
            when {
                amount != null -> KeyValueRow(row.label, if (row.subtract) "−${amount.format()}" else amount.format())
                row.key in r.unavailableFields -> KeyValueRow(row.label, "Not visible", valueColor = SmartCartTheme.semantic.warning)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
        KeyValueRow("Estimated total", r.totalEstimate?.format() ?: "—", emphasize = true)
        Spacer(Modifier.size(Spacing.sm))
        Text("This is an estimate. The final price, including any fees and taxes, is shown in ${r.provider.displayName} before you pay.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Footnotes(q: ComparisonQuote) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("Checked ${q.coverage.checked.size} of ${q.providerResults.size} apps at ${Format.dateTime(q.createdAt)}.", style = MaterialTheme.typography.bodySmall)
        if (q.missingFields.isNotEmpty()) Text("Some fees weren't visible: ${q.missingFields.joinToString { it.replace('_', ' ') }}.", style = MaterialTheme.typography.bodySmall)
        if (q.assumptions.isNotEmpty()) {
            Text("Assumptions", style = MaterialTheme.typography.labelLarge)
            q.assumptions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text("Prices and availability change often. The app you choose shows the final price before you pay. SmartCart never makes payments.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
