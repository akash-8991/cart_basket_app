package com.smartcart.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KpiSet(
    val totalOrders: Int,
    val totalAmount: Money,
    val averageOrderValue: Money,
    val ordersPerWeek: Double,
    val daysBetweenOrders: Double? = null,
)

@Serializable
data class KpiDelta(
    val totalOrdersPct: Double? = null,
    val totalAmountPct: Double? = null,
    val ordersPerWeekPct: Double? = null,
)

@Serializable
data class Split(val key: String, val label: String, val orders: Int, val amount: Money)

@Serializable
enum class FreshnessStatus {
    @SerialName("fresh") FRESH,
    @SerialName("stale") STALE,
    @SerialName("never_imported") NEVER_IMPORTED,
}

@Serializable
data class DataFreshness(val status: FreshnessStatus, val lastImportedAt: String? = null)

@Serializable
enum class CategoryStatus {
    @SerialName("ready") READY,
    @SerialName("setup_required") SETUP_REQUIRED,
    @SerialName("unavailable") UNAVAILABLE,
}

@Serializable
data class CategoryCard(
    val category: Category,
    val title: String,
    val providers: List<ProviderKey>,
    val status: CategoryStatus,
)

@Serializable
data class ComparisonSummary(
    val quoteId: String,
    val category: Category,
    val title: String,
    val recommendedProvider: ProviderKey? = null,
    val totalEstimate: Money? = null,
    val createdAt: String,
    val status: ComparisonStatus,
)

@Serializable
data class DashboardSummary(
    val month: String,
    val availableMonths: List<String>,
    val currency: String,
    val kpis: KpiSet,
    val previousKpis: KpiSet? = null,
    val deltaVsPrevious: KpiDelta,
    val providerSplit: List<Split>,
    val categorySplit: List<Split>,
    val freshness: DataFreshness,
    val categories: List<CategoryCard>,
    val suggestionsPreview: List<Recommendation>,
    val recentComparisons: List<ComparisonSummary>,
) {
    val isEmpty: Boolean get() = kpis.totalOrders == 0 && freshness.status == FreshnessStatus.NEVER_IMPORTED
}

@Serializable
data class MonthMetrics(
    val month: String,
    val kpis: KpiSet,
    val providerSplit: List<Split>,
    val categorySplit: List<Split>,
)

@Serializable
data class MonthlyMetrics(val currency: String, val months: List<MonthMetrics>)
