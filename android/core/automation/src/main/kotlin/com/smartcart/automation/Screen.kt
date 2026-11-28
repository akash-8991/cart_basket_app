package com.smartcart.automation

/**
 * One node of a partner app's screen, as the accessibility framework exposes it. The runtime only ever
 * works with these semantic properties: there are no coordinates anywhere in the automation model.
 *
 * [id] is the full resource name ("com.example:id/search_box"). Text of password fields is never read
 * ([text] is null when [password] is true). [handle] is the platform object the driver acts on.
 */
data class UiNode(
    val id: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    val className: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
    val children: List<UiNode> = emptyList(),
    val handle: Any? = null,
) {
    fun flatten(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        children.forEach { yieldAll(it.flatten()) }
    }

    /** Everything a person could read on this node, for matching and hazard checks. */
    val words: String get() = listOfNotNull(text, desc, hint).joinToString(" ")
}

/** What is on screen right now: the foreground app's package and its node tree. */
data class ScreenSnapshot(val packageName: String, val root: UiNode) {
    val nodes: List<UiNode> by lazy { root.flatten().toList() }
    fun find(s: Selector): List<UiNode> = nodes.filter(s::matches)
    fun first(s: Selector): UiNode? = nodes.firstOrNull(s::matches)
    fun has(s: Selector): Boolean = nodes.any(s::matches)

    /** Android's own dialog chrome: an app showing an AlertDialog the adapter doesn't know about. */
    val isSystemStyleDialog: Boolean get() = nodes.any { it.id == "android:id/alertTitle" || it.id == "android:id/button1" }
}

/** Semantic selectors only (A-13): resource ID, content description or visible text. */
sealed interface Selector {
    fun matches(n: UiNode): Boolean

    /** A resource ID; "search_box" matches any package's ":id/search_box", "pkg:id/x" matches exactly. */
    data class Id(val id: String) : Selector {
        override fun matches(n: UiNode) = n.id != null && (if (':' in id) n.id == id else n.id.endsWith(":id/$id"))
    }

    data class Desc(val desc: String) : Selector {
        override fun matches(n: UiNode) = n.desc.equals(desc, ignoreCase = true)
    }

    data class Text(val text: String) : Selector {
        override fun matches(n: UiNode) = n.text?.trim().equals(text.trim(), ignoreCase = true)
    }

    /** A node with this ID whose text is [text] (e.g. one row's name in a result list). */
    data class IdWithText(val id: String, val text: String) : Selector {
        override fun matches(n: UiNode) = Id(id).matches(n) && Text(text).matches(n)
    }
}

/** A screen an adapter knows: every [required] selector must be present. [hazard] marks screens never to act on. */
data class ScreenSpec(val name: String, val required: List<Selector>, val hazard: Hazard? = null) {
    fun matches(s: ScreenSnapshot) = required.all(s::has)
}
