package com.smartcart.app.feature.suggestions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartcart.app.AppContainer
import com.smartcart.app.feature.home.confidenceTone
import com.smartcart.app.ui.common.ErrorCopy
import com.smartcart.app.ui.common.Load
import com.smartcart.app.ui.common.LoadContent
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.app.ui.common.ScreenScaffold
import com.smartcart.app.ui.common.toLoad
import com.smartcart.data.AnalyticsEventName
import com.smartcart.data.ApiResult
import com.smartcart.designsystem.AppCard
import com.smartcart.designsystem.Banner
import com.smartcart.designsystem.EmptyState
import com.smartcart.designsystem.Pill
import com.smartcart.designsystem.Spacing
import com.smartcart.designsystem.Tone
import com.smartcart.model.ApiError
import com.smartcart.model.Objective
import com.smartcart.model.ProviderStatus
import com.smartcart.model.QuoteRequest
import com.smartcart.model.Recommendation
import com.smartcart.model.RecommendationList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SuggestionsViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow<Load<RecommendationList>>(Load.Loading)
    val error = MutableStateFlow<ApiError?>(null)
    val starting = MutableStateFlow<String?>(null)

    init { load() }

    fun load() { state.value = Load.Loading; viewModelScope.launch { state.value = c.recommendations.list().toLoad() } }

    fun notUseful(r: Recommendation) = viewModelScope.launch {
        c.recommendations.feedback(r.recommendationId, false)
        c.analytics.track(AnalyticsEventName.SUGGESTION_FEEDBACK, mapOf("useful" to false))
        load()
    }

    /** FR-8: selecting a suggestion always runs a fresh cross-app comparison before any automation. */
    fun start(r: Recommendation, onQuote: (String) -> Unit) {
        starting.value = r.recommendationId
        c.analytics.track(AnalyticsEventName.SUGGESTION_OPENED, mapOf("category" to r.category.key))
        viewModelScope.launch {
            val eligible = c.providers.providers.value.filter { it.category == r.category && !it.hidden && it.status != ProviderStatus.DISABLED }.map { it.key }
            when (val q = c.comparisons.create(QuoteRequest(r.category, r.prefillLines, null, eligible, r.usualProvider, Objective.LOWEST_TOTAL))) {
                is ApiResult.Success -> onQuote(q.value)
                is ApiResult.Failure -> error.value = q.error
            }
            starting.value = null
        }
    }
}

// ---------------------------------------------------------------- S-SUG-01
@Composable
fun SuggestionsScreen(onBack: () -> Unit, onComparison: (String) -> Unit, onConnectHistory: () -> Unit) {
    val c = LocalAppContainer.current
    val vm = viewModel { SuggestionsViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()
    ScreenScaffold("Suggestions", onBack) { padding ->
        LoadContent(state, onRetry = vm::load) {
            val list = (state as Load.Ready).value
            if (list.recommendations.isEmpty()) {
                EmptyState("No suggestions yet", "Suggestions appear once there's enough order history to spot patterns.", Modifier.padding(padding), actionLabel = "Connect order history", onAction = onConnectHistory)
                return@LoadContent
            }
            LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                error?.let { e -> item { Banner(ErrorCopy.body(e), tone = Tone.DANGER, title = ErrorCopy.title(e)) } }
                items(list.recommendations, key = { it.recommendationId }) { r ->
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Pill("${r.confidence.name.lowercase().replaceFirstChar { it.uppercase() }} confidence", tone = confidenceTone(r.confidence))
                        }
                        Spacer(Modifier.size(Spacing.xs))
                        Text("Why: ${r.explanation}", style = MaterialTheme.typography.bodyMedium)
                        if (r.prefillLines.isNotEmpty()) Text(r.prefillLines.joinToString { "${it.label} × ${it.quantity}" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        r.usualProvider?.let { Text("Usually from ${it.displayName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Row {
                            TextButton(onClick = { vm.notUseful(r) }) { Text("Not useful") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { vm.start(r, onComparison) }, enabled = starting == null) { Text(if (starting == r.recommendationId) "Checking prices…" else "Compare prices") }
                        }
                    }
                }
                item { Text(list.disclaimer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.md)) }
            }
        }
    }
}
