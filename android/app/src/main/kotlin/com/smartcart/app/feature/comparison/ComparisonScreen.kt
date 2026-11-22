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
import com.smartcart.app.R
import com.smartcart.app.ui.common.ErrorCopy
import com.smartcart.app.ui.common.costFieldLabel
import com.smartcart.app.ui.common.costRowRes
import com.smartcart.app.ui.common.label
import com.smartcart.app.ui.common.labelRes
import androidx.compose.ui.res.stringResource
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

    ScreenScaffold(stringResource(R.string.cmp_title), onBack) { padding ->
        LoadContent(s.quote, onRetry = vm::load, loadingLabel = stringResource(R.string.cmp_loading)) {
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
                            actionLabel = if (e.code in setOf(ErrorCode.PROVIDER_DISABLED, ErrorCode.POLICY_BLOCKED) && s.lastChosen != null) stringResource(R.string.common_continue_manually) else null,
                            onAction = { s.lastChosen?.let { onManual(it, q.quoteId) } },
                        )
                    }
                }
                if (recommended != null && q.status != ComparisonStatus.IN_PROGRESS) {
                    item { RecommendationCard(q, recommended, busy = s.busy == recommended, onUse = { vm.choose(recommended) }) }
                }
                item { SectionHeader(stringResource(if (q.providerResults.size > 1) R.string.cmp_all_checked else R.string.cmp_one_checked)) }
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
            title = { Text(stringResource(R.string.cmp_switch_title, p.displayName)) },
            text = {
                val fromName = from?.displayName.orEmpty()
                val savings = q?.recommendation?.savingsVsSelected?.takeIf { p == q.recommendation.provider }
                Text(
                    if (savings != null) stringResource(R.string.cmp_switch_body_savings, fromName, p.displayName, savings.format())
                    else stringResource(R.string.cmp_switch_body, fromName, p.displayName),
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmSwitch) { Text(stringResource(R.string.cmp_use, p.displayName)) } },
            dismissButton = { TextButton(onClick = vm::dismissSwitch) { Text(stringResource(R.string.cmp_keep, from?.displayName.orEmpty())) } },
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
            ProgressRow(stringResource(R.string.cmp_progress, q.providerResults.size, done), progress = if (q.providerResults.isEmpty()) null else done.toFloat() / q.providerResults.size)
        }
        ComparisonStatus.COMPLETE -> Unit
        ComparisonStatus.PARTIAL_COVERAGE -> Banner(
            if (failed.isNotEmpty()) stringResource(R.string.cmp_partial_body_named, failed) else stringResource(R.string.cmp_partial_body),
            tone = Tone.WARNING, title = stringResource(R.string.cmp_partial_title),
        )
        ComparisonStatus.STALE -> Banner(stringResource(R.string.cmp_stale_body, Format.dateTime(q.createdAt)), tone = Tone.WARNING, title = stringResource(R.string.cmp_stale_title), actionLabel = stringResource(R.string.cmp_check_again), onAction = onRecompare)
        ComparisonStatus.INCOMPARABLE -> Banner(q.recommendation.reason, tone = Tone.INFO, title = stringResource(R.string.cmp_incomparable_title))
        ComparisonStatus.UNAVAILABLE -> Banner(q.recommendation.reason, tone = Tone.WARNING, title = stringResource(R.string.cmp_unavailable_title))
        ComparisonStatus.PRICE_CHANGED -> Banner(
            stringResource(
                R.string.cmp_price_changed_body,
                q.previousTotalEstimate?.format().orEmpty(),
                q.providerResults.firstOrNull { it.provider == q.selectedProvider }?.totalEstimate?.format().orEmpty(),
            ),
            tone = Tone.WARNING, title = stringResource(R.string.cmp_price_changed_title),
        )
        ComparisonStatus.MANUAL_ONLY -> Banner(q.recommendation.reason, tone = Tone.INFO, title = stringResource(R.string.cmp_manual_title))
    }
}

@Composable
private fun RecommendationCard(q: ComparisonQuote, p: ProviderKey, busy: Boolean, onUse: () -> Unit) {
    val r = q.providerResults.first { it.provider == p }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(q.badgeLabel.labelRes()?.let { stringResource(it) }.orEmpty(), tone = if (q.badgeLabel == RecommendationLabel.CHEAPEST) Tone.SUCCESS else Tone.INFO)
            Spacer(Modifier.width(Spacing.sm))
            Pill(q.confidence.label(), tone = confidenceTone(q.confidence))
        }
        Spacer(Modifier.size(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderMonogram(p.displayName)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(p.displayName, style = MaterialTheme.typography.titleMedium)
                r.etaMinutes?.let { Text(stringResource(R.string.cmp_about_min, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(r.totalEstimate?.format() ?: stringResource(R.string.common_dash), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.size(Spacing.sm))
        val savings = q.recommendation.savingsVsSelected
        Text(
            if (savings != null && savings.amountMinor > 0) {
                stringResource(R.string.cmp_reason_savings, savings.format(), q.selectedProvider?.displayName.orEmpty(), Format.time(r.capturedAt ?: q.createdAt), p.displayName)
            } else {
                stringResource(R.string.cmp_reason_plain, q.recommendation.reason, Format.time(r.capturedAt ?: q.createdAt), p.displayName)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(Spacing.md))
        PrimaryButton(stringResource(R.string.cmp_use, p.displayName), onClick = onUse, loading = busy)
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
    val availability = stringResource(r.availability.labelRes())
    val desc = stringResource(R.string.cmp_result_cd, r.provider.displayName, availability) +
        (r.totalEstimate?.let { stringResource(R.string.cmp_result_cd_total, it.format()) } ?: "") +
        (r.etaMinutes?.let { stringResource(R.string.cmp_result_cd_eta, it) } ?: "")
    AppCard(modifier = Modifier.semantics { contentDescription = desc }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderMonogram(r.provider.displayName, size = 36)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.provider.displayName, style = MaterialTheme.typography.titleSmall)
                    if (r.provider == quote.selectedProvider) { Spacer(Modifier.width(Spacing.xs)); Pill(stringResource(R.string.cmp_your_pick)) }
                    if (isRecommended) { Spacer(Modifier.width(Spacing.xs)); Pill(stringResource(R.string.cmp_recommended), tone = Tone.SUCCESS) }
                }
                val meta = listOfNotNull(r.etaMinutes?.let { stringResource(R.string.cmp_eta_short, it) }, availability.takeIf { r.availability != Availability.AVAILABLE }).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(r.totalEstimate?.format() ?: stringResource(R.string.common_dash), style = MaterialTheme.typography.titleMedium)
        }
        r.lines.filter { it.match != LineMatch.EXACT }.forEach { l ->
            val detail = listOfNotNull(stringResource(l.match.labelRes()), l.matchedSize?.let { "($it)" }, l.note?.let { "— $it" }).joinToString(" ")
            Text(stringResource(R.string.cmp_line_issue, l.requestedName, detail), style = MaterialTheme.typography.bodySmall, color = SmartCartTheme.semantic.warning)
        }
        r.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (r.unavailableFields.isNotEmpty()) {
            Text(stringResource(R.string.cmp_not_visible, r.unavailableFields.map { costFieldLabel(it) }.joinToString()), style = MaterialTheme.typography.bodySmall, color = SmartCartTheme.semantic.warning)
        }
        if (quote.status != ComparisonStatus.IN_PROGRESS) {
            Row {
                if (r.totalEstimate != null) TextButton(onClick = onBreakdown) { Text(stringResource(R.string.cmp_cost_breakdown)) }
                Spacer(Modifier.weight(1f))
                when {
                    providerStatus != ProviderStatus.ENABLED || quote.status == ComparisonStatus.MANUAL_ONLY ->
                        TextButton(onClick = onManual) { Text(stringResource(R.string.cmp_open_manually)) }
                    r.availability in setOf(Availability.AVAILABLE, Availability.PARTIAL) && !isRecommended ->
                        TextButton(onClick = onChoose, enabled = !busy) { Text(stringResource(if (busy) R.string.cmp_please_wait else R.string.cmp_choose)) }
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
        Text(stringResource(R.string.cmp_estimate_title, r.provider.displayName), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.cmp_checked_at, Format.dateTime(r.capturedAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(Spacing.md))
        r.lines.forEach { l ->
            val name = listOfNotNull(l.matchedName ?: l.requestedName, l.matchedSize).joinToString(" ")
            KeyValueRow(stringResource(R.string.cmp_line_item, name, l.quantity), l.lineTotal?.format() ?: stringResource(l.match.labelRes()))
        }
        HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
        r.components.rows().forEach { row ->
            val amount = row.amount
            when {
                amount != null -> KeyValueRow(stringResource(costRowRes(row.key)!!), if (row.subtract) stringResource(R.string.cmp_minus, amount.format()) else amount.format())
                row.key in r.unavailableFields -> KeyValueRow(stringResource(costRowRes(row.key)!!), stringResource(R.string.cmp_not_visible_value), valueColor = SmartCartTheme.semantic.warning)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
        KeyValueRow(stringResource(R.string.cmp_estimated_total), r.totalEstimate?.format() ?: stringResource(R.string.common_dash), emphasize = true)
        Spacer(Modifier.size(Spacing.sm))
        Text(stringResource(R.string.cmp_estimate_note, r.provider.displayName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Footnotes(q: ComparisonQuote) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.cmp_checked_count, q.coverage.checked.size, q.providerResults.size, Format.dateTime(q.createdAt)), style = MaterialTheme.typography.bodySmall)
        if (q.missingFields.isNotEmpty()) Text(stringResource(R.string.cmp_missing_fees, q.missingFields.map { costFieldLabel(it).lowercase() }.joinToString()), style = MaterialTheme.typography.bodySmall)
        if (q.assumptions.isNotEmpty()) {
            Text(stringResource(R.string.cmp_assumptions), style = MaterialTheme.typography.labelLarge)
            q.assumptions.forEach { Text(stringResource(R.string.cmp_bullet, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(stringResource(R.string.cmp_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
