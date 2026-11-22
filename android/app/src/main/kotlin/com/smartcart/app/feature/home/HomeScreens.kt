package com.smartcart.app.feature.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalGroceryStore
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartcart.app.ui.common.ErrorCopy
import com.smartcart.app.ui.common.Format
import com.smartcart.app.ui.common.Load
import com.smartcart.app.ui.common.LoadContent
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.app.ui.common.ScreenScaffold
import com.smartcart.app.ui.common.toLoad
import com.smartcart.data.Analytics
import com.smartcart.data.AnalyticsEventName
import com.smartcart.data.DashboardRepository
import com.smartcart.data.FeatureFlags
import com.smartcart.data.FlagKeys
import com.smartcart.designsystem.AppCard
import com.smartcart.designsystem.Banner
import com.smartcart.designsystem.EmptyState
import com.smartcart.designsystem.ErrorState
import com.smartcart.designsystem.Pill
import com.smartcart.designsystem.SectionHeader
import com.smartcart.designsystem.SkeletonBlock
import com.smartcart.designsystem.SmartCartTheme
import com.smartcart.designsystem.Spacing
import com.smartcart.designsystem.Tone
import com.smartcart.model.Category
import com.smartcart.model.CategoryCard
import com.smartcart.model.CategoryStatus
import com.smartcart.model.ComparisonStatus
import com.smartcart.model.Confidence
import com.smartcart.model.DashboardSummary
import com.smartcart.model.ErrorCode
import com.smartcart.model.FreshnessStatus
import com.smartcart.model.MonthlyMetrics
import com.smartcart.model.Split
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeViewModel(private val repo: DashboardRepository, private val analytics: Analytics) : ViewModel() {
    private val _state = MutableStateFlow<Load<DashboardSummary>>(Load.Loading)
    val state: StateFlow<Load<DashboardSummary>> = _state
    private var month: String? = null

    init { load() }

    fun load(m: String? = month) {
        month = m
        _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.summary(m).toLoad() }
    }

    fun selectMonth(m: String) {
        analytics.track(AnalyticsEventName.HOME_MONTH_CHANGED)
        load(m)
    }
}

fun categoryIcon(c: Category): ImageVector = when (c) {
    Category.TRAVEL -> Icons.Outlined.DirectionsCar
    Category.FOOD -> Icons.Outlined.Restaurant
    Category.GROCERY -> Icons.Outlined.LocalGroceryStore
    Category.SHOPPING -> Icons.Outlined.ShoppingBag
}

@Composable
fun categoryColor(c: Category): Color = when (c) {
    Category.TRAVEL -> SmartCartTheme.semantic.travel
    Category.FOOD -> SmartCartTheme.semantic.food
    Category.GROCERY -> SmartCartTheme.semantic.grocery
    Category.SHOPPING -> SmartCartTheme.semantic.shopping
}

fun confidenceTone(c: Confidence) = when (c) {
    Confidence.HIGH -> Tone.SUCCESS
    Confidence.MEDIUM -> Tone.INFO
    Confidence.LOW -> Tone.WARNING
}

// ---------------------------------------------------------------- S-HOME-01
@Composable
fun HomeScreen(
    onCategory: (Category) -> Unit,
    onMonth: (String) -> Unit,
    onSuggestions: () -> Unit,
    onComparison: (String) -> Unit,
    onConnectHistory: () -> Unit,
    onSettings: () -> Unit,
    onChatHistory: () -> Unit,
) {
    val c = LocalAppContainer.current
    val vm = viewModel { HomeViewModel(c.dashboard, c.analytics) }
    val state by vm.state.collectAsStateWithLifecycle()
    val flags by c.flags.values.collectAsStateWithLifecycle()
    val account by c.auth.state.collectAsStateWithLifecycle()
    val paused by c.account.automationPaused.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { c.analytics.track(AnalyticsEventName.SCREEN_VIEW, mapOf("screen" to "S-HOME-01")) }

    ScreenScaffold(
        title = "SmartCart",
        onBack = null,
        actions = {
            IconButton(onClick = onChatHistory) { Icon(Icons.Outlined.History, contentDescription = "Conversation history") }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // 1. Header with account and security status
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = Spacing.xs)) {
                    Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = SmartCartTheme.semantic.success, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    val who = (account as? com.smartcart.model.AccountState.SignedIn)?.userId
                    Text((who?.let { "Signed in as $it · " } ?: "") + "Session protected on this device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (paused) Banner("All automation is paused. Resume it in Settings → Help.", tone = Tone.WARNING)
            }
            // 2. KPI summary
            item {
                when (val s = state) {
                    Load.Loading -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { SkeletonBlock(height = 28); SkeletonBlock(height = 150) }
                    is Load.Failed -> AppCard {
                        ErrorState(ErrorCopy.title(s.error), "Your summary couldn't load. Categories still work.", onRetry = { vm.load() }, offline = s.error.code == ErrorCode.OFFLINE)
                    }
                    is Load.Ready -> KpiSection(s.value, onMonthSelected = vm::selectMonth, onDetails = { onMonth(s.value.month) }, onConnect = onConnectHistory)
                }
            }
            // 3. Category cards directly below the KPIs
            item { SectionHeader("What do you need?") }
            val cards = (state as? Load.Ready)?.value?.categories ?: defaultCards()
            val visible = cards.filter { flags[FlagKeys.category(it.category)] == true }
            items(visible, key = { it.category.key }) { card -> CategoryCardView(card, onClick = { c.analytics.track(AnalyticsEventName.CATEGORY_SELECTED, mapOf("category" to card.category.key)); onCategory(card.category) }) }

            // 4. Suggestions and recent comparisons
            val ready = (state as? Load.Ready)?.value
            if (ready != null && ready.suggestionsPreview.isNotEmpty()) {
                item { SectionHeader("Suggested for you", trailing = { TextButton(onClick = onSuggestions) { Text("See all") } }) }
                items(ready.suggestionsPreview, key = { it.recommendationId }) { r ->
                    AppCard(onClick = onSuggestions) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Pill("${r.confidence.name.lowercase().replaceFirstChar { it.uppercase() }} confidence", tone = confidenceTone(r.confidence))
                        }
                        Text(r.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (ready != null && ready.recentComparisons.isNotEmpty()) {
                item { SectionHeader("Recent comparisons") }
                items(ready.recentComparisons, key = { it.quoteId }) { rc ->
                    AppCard(onClick = { onComparison(rc.quoteId) }) {
                        Text(rc.title, style = MaterialTheme.typography.titleSmall)
                        val who = rc.recommendedProvider?.displayName ?: "No recommendation"
                        val total = rc.totalEstimate?.format()?.let { " · est. $it" } ?: ""
                        Text("$who$total · ${Format.dateTime(rc.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (rc.status != ComparisonStatus.COMPLETE) Pill(rc.status.name.lowercase().replace('_', ' '), tone = Tone.WARNING)
                    }
                }
            }
            // 5. Chat entry (composer lives in category chat to keep it category-scoped)
            item {
                Spacer(Modifier.size(Spacing.sm))
                AppCard(onClick = { onCategory(Category.GROCERY) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null)
                        Spacer(Modifier.width(Spacing.md))
                        Text("Tell me what you need… pick a category above or start with groceries", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun defaultCards() = listOf(
    CategoryCard(Category.TRAVEL, "Travel", listOf(com.smartcart.model.ProviderKey.UBER, com.smartcart.model.ProviderKey.RAPIDO, com.smartcart.model.ProviderKey.OLA), CategoryStatus.READY),
    CategoryCard(Category.FOOD, "Food ordering", listOf(com.smartcart.model.ProviderKey.ZOMATO, com.smartcart.model.ProviderKey.SWIGGY), CategoryStatus.READY),
    CategoryCard(Category.GROCERY, "Grocery", listOf(com.smartcart.model.ProviderKey.BLINKIT, com.smartcart.model.ProviderKey.BIGBASKET, com.smartcart.model.ProviderKey.SWIGGY_INSTAMART), CategoryStatus.READY),
)

@Composable
private fun KpiSection(s: DashboardSummary, onMonthSelected: (String) -> Unit, onDetails: () -> Unit, onConnect: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        // Month selector
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            s.availableMonths.reversed().forEach { m ->
                FilterChip(selected = m == s.month, onClick = { onMonthSelected(m) }, label = { Text(Format.monthShort(m)) }, modifier = Modifier.heightIn(min = Spacing.touchTarget))
            }
        }
        if (s.isEmpty) {
            AppCard {
                EmptyState(
                    title = "No orders yet",
                    body = "Connect your order history to see monthly totals and frequency. You choose which apps and fields.",
                    actionLabel = "Connect order history",
                    onAction = onConnect,
                )
            }
            return@Column
        }
        AppCard(onClick = onDetails) {
            Text(Format.month(s.month), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.size(Spacing.sm))
            val prevMonth = s.availableMonths.getOrNull(s.availableMonths.indexOf(s.month) - 1)?.let { Format.monthShort(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Kpi("Total orders", s.kpis.totalOrders.toString(), Format.pct(s.deltaVsPrevious.totalOrdersPct), prevMonth, Modifier.weight(1f))
                Kpi("Total spent", s.kpis.totalAmount.format(), Format.pct(s.deltaVsPrevious.totalAmountPct), prevMonth, Modifier.weight(1f))
                Kpi("Orders / week", Format.oneDecimal(s.kpis.ordersPerWeek), Format.pct(s.deltaVsPrevious.ordersPerWeekPct), prevMonth, Modifier.weight(1f))
            }
            Spacer(Modifier.size(Spacing.sm))
            Text(
                "Avg order ${s.kpis.averageOrderValue.format()}" + (s.kpis.daysBetweenOrders?.let { " · every ${Format.oneDecimal(it)} days" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.providerSplit.isNotEmpty()) {
                Spacer(Modifier.size(Spacing.md))
                SplitBars(s.providerSplit.sortedByDescending { it.amount.amountMinor }.take(4))
            }
            Spacer(Modifier.size(Spacing.sm))
            FreshnessLine(s)
        }
        if (s.freshness.status == FreshnessStatus.STALE) {
            Banner("Your order history was last imported ${Format.date(s.freshness.lastImportedAt)}. Totals may be out of date.", tone = Tone.WARNING, actionLabel = "Refresh history", onAction = onConnect)
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, delta: String?, prevMonth: String?, modifier: Modifier) {
    // Merged so TalkBack reads "Total orders, 14, +16.7% vs last month" as one item.
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (delta != null && prevMonth != null) Text("$delta vs $prevMonth", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SplitBars(splits: List<Split>) {
    val total = splits.sumOf { it.amount.amountMinor }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        splits.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clearAndSetSemantics { contentDescription = "${s.label}: ${s.orders} orders, ${s.amount.format()}" }) {
                Text(s.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(96.dp))
                LinearProgressIndicator(progress = { s.amount.amountMinor.toFloat() / total }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Spacing.sm))
                Text(s.amount.format(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FreshnessLine(s: DashboardSummary) {
    val text = when (s.freshness.status) {
        FreshnessStatus.FRESH -> "Up to date · last imported ${Format.dateTime(s.freshness.lastImportedAt)}"
        FreshnessStatus.STALE -> "Out of date · last imported ${Format.dateTime(s.freshness.lastImportedAt)}"
        FreshnessStatus.NEVER_IMPORTED -> "No history imported"
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CategoryCardView(card: CategoryCard, onClick: () -> Unit) {
    AppCard(onClick = onClick, modifier = Modifier.semantics { contentDescription = "${card.title}. ${card.providers.joinToString { it.displayName }}" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(categoryIcon(card.category), contentDescription = null, tint = categoryColor(card.category), modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.titleMedium)
                Text(card.providers.joinToString(" · ") { it.displayName }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (card.status) {
                CategoryStatus.READY -> Unit
                CategoryStatus.SETUP_REQUIRED -> Pill("Set up", tone = Tone.INFO)
                CategoryStatus.UNAVAILABLE -> Pill("Unavailable", tone = Tone.WARNING)
            }
        }
    }
}

// ---------------------------------------------------------------- S-HOME-02
class MonthViewModel(private val repo: DashboardRepository, private val month: String) : ViewModel() {
    val state = MutableStateFlow<Load<MonthlyMetrics>>(Load.Loading)
    init { load() }
    fun load() {
        state.value = Load.Loading
        viewModelScope.launch { state.value = repo.monthly("2026-01", month).toLoad() }
    }
}

@Composable
fun MonthDetailScreen(month: String, onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val vm = viewModel(key = "month-$month") { MonthViewModel(c.dashboard, month) }
    val state by vm.state.collectAsStateWithLifecycle()
    var categoryFilter by rememberSaveable { mutableStateOf<String?>(null) }
    ScreenScaffold(Format.month(month), onBack) { padding ->
        LoadContent(state, onRetry = vm::load) {
            val data = (state as Load.Ready).value
            val current = data.months.lastOrNull { it.month == month } ?: data.months.lastOrNull()
            LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl)) {
                if (current == null) {
                    item { EmptyState("No data for this month", "Import order history to see details.") }
                    return@LazyColumn
                }
                item {
                    AppCard {
                        com.smartcart.designsystem.KeyValueRow("Orders", current.kpis.totalOrders.toString())
                        com.smartcart.designsystem.KeyValueRow("Total spent", current.kpis.totalAmount.format())
                        com.smartcart.designsystem.KeyValueRow("Average order", current.kpis.averageOrderValue.format())
                        com.smartcart.designsystem.KeyValueRow("Orders per week", Format.oneDecimal(current.kpis.ordersPerWeek))
                        current.kpis.daysBetweenOrders?.let { com.smartcart.designsystem.KeyValueRow("Days between orders", Format.oneDecimal(it)) }
                    }
                }
                item { SectionHeader("By category") }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        FilterChip(selected = categoryFilter == null, onClick = { categoryFilter = null }, label = { Text("All") })
                        current.categorySplit.forEach { s -> FilterChip(selected = categoryFilter == s.key, onClick = { categoryFilter = s.key }, label = { Text(s.label) }) }
                    }
                }
                item { AppCard { SplitBars(current.categorySplit.filter { categoryFilter == null || it.key == categoryFilter }) } }
                item { SectionHeader("By app") }
                item { AppCard { SplitBars(current.providerSplit.sortedByDescending { it.amount.amountMinor }) } }
                item { SectionHeader("Month on month") }
                items(data.months.reversed(), key = { it.month }) { m ->
                    com.smartcart.designsystem.KeyValueRow(Format.month(m.month), "${m.kpis.totalOrders} orders · ${m.kpis.totalAmount.format()}", emphasize = m.month == month)
                }
                item {
                    Spacer(Modifier.size(Spacing.md))
                    Text("Totals are calculated from your imported orders, including fees and discounts. Refunded and cancelled orders are excluded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
