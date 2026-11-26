package com.smartcart.server.providers

import com.smartcart.server.flags.Flags
import com.smartcart.server.me.MeRepository
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

data class Capabilities(val cartAutomation: String, val historyImport: String, val deepLink: String, val comparison: String)

data class ProviderDef(val key: String, val displayName: String, val category: String, val capabilities: Capabilities)

/**
 * The provider catalogue as designed for the frozen frontend (capabilities from the provider fixture;
 * ProviderRegistryTest keeps them identical). Package names are deliberately absent until verified on a
 * device (provider-matrix.md).
 */
object ProviderRegistry {
    private val full = Capabilities("supported", "supported", "cart", "supported")
    private val ride = Capabilities("ride_setup_only", "supported", "cart", "supported")

    val all: List<ProviderDef> = listOf(
        ProviderDef("uber", "Uber", "travel", ride),
        ProviderDef("ola", "Ola", "travel", ride),
        ProviderDef("rapido", "Rapido", "travel", ride),
        ProviderDef("zomato", "Zomato", "food", full),
        ProviderDef("swiggy", "Swiggy", "food", full),
        ProviderDef("blinkit", "Blinkit", "grocery", full),
        ProviderDef("bigbasket", "BigBasket", "grocery", Capabilities("supported", "supported", "app_launch", "partial")),
        ProviderDef("swiggy_instamart", "Swiggy Instamart", "grocery", full),
        ProviderDef("amazon", "Amazon", "shopping", full),
        ProviderDef("flipkart", "Flipkart", "shopping", full),
        ProviderDef("zepto", "Zepto", "grocery", full),
    )

    fun find(key: String): ProviderDef? = all.firstOrNull { it.key == key }
}

data class Approval(val providerKey: String, val adapterVersion: String, val approvedBy: String, val recordUrl: String, val approvedAt: Instant)

data class ProviderView(
    val def: ProviderDef,
    val status: String,
    val automationConsent: Boolean,
    val historyConsent: Boolean,
    val adapterVersion: String?,
)

class ProviderApprovals(private val db: DataSource) {
    fun active(): Map<String, Approval> = db.connection.use { c ->
        c.createStatement().executeQuery("select provider_key, adapter_version, approved_by, record_url, approved_at from provider_approvals where revoked_at is null").use { rs ->
            buildMap { while (rs.next()) put(rs.getString(1), Approval(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant())) }
        }
    }

    fun approve(a: Approval) = db.connection.use { c ->
        c.prepareStatement(
            """insert into provider_approvals(provider_key, adapter_version, approved_by, record_url, approved_at) values (?,?,?,?,?)
               on conflict (provider_key) do update set adapter_version = excluded.adapter_version, approved_by = excluded.approved_by,
               record_url = excluded.record_url, approved_at = excluded.approved_at, revoked_at = null""",
        ).use { st ->
            st.setString(1, a.providerKey); st.setString(2, a.adapterVersion); st.setString(3, a.approvedBy); st.setString(4, a.recordUrl); st.setTimestamp(5, Timestamp.from(a.approvedAt))
            st.executeUpdate()
        }
    }

    fun revoke(key: String, now: Instant): Boolean = db.connection.use { c ->
        c.prepareStatement("update provider_approvals set revoked_at = ? where provider_key = ? and revoked_at is null").use { st ->
            st.setTimestamp(1, Timestamp.from(now)); st.setString(2, key); st.executeUpdate() == 1
        }
    }
}

/**
 * Provider status, highest precedence first:
 *  1. `provider.<key>.kill` on → `kill_switched` (incident response; overrides everything);
 *  2. `provider.<key>.enabled` off → `disabled` (the default for every provider);
 *  3. no active approval record (when approvals are required) → `pending_approval`;
 *  4. otherwise → `enabled`.
 */
class ProviderService(
    private val flags: Flags,
    private val approvals: ProviderApprovals,
    private val me: MeRepository,
    private val requireApproval: Boolean,
) {
    fun status(key: String, approved: Map<String, Approval> = approvals.active()): String = when {
        flags.isOn("provider.$key.kill") -> "kill_switched"
        !flags.isOn("provider.$key.enabled") -> "disabled"
        requireApproval && key !in approved -> "pending_approval"
        else -> "enabled"
    }

    /** True only for a provider a task may actually run against right now (used by Phase 4). */
    fun isUsable(key: String): Boolean = status(key) == "enabled"

    fun list(userId: String): List<ProviderView> {
        val approved = approvals.active()
        val consents = me.consents(userId).associate { it.key to it.granted }
        return ProviderRegistry.all.map { p ->
            ProviderView(
                def = p,
                status = status(p.key, approved),
                automationConsent = consents["automation.${p.key}"] ?: false,
                historyConsent = consents["history.${p.key}"] ?: false,
                adapterVersion = approved[p.key]?.adapterVersion,
            )
        }
    }
}
