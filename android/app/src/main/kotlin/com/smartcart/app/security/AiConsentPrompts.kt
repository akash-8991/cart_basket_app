package com.smartcart.app.security

import android.content.Context

/**
 * Remembers, per user on this device, that the AI-processing question was answered (CR-003 §1), so
 * "Not now" isn't asked again on every chat. The decision itself is stored on the server as a consent.
 */
class AiConsentPrompts(context: Context?) {
    private val prefs = context?.applicationContext?.getSharedPreferences("prompts", Context.MODE_PRIVATE)
    private val memory = mutableSetOf<String>()

    fun asked(userId: String): Boolean = prefs?.getBoolean("ai_asked:$userId", false) ?: (userId in memory)

    fun markAsked(userId: String) {
        prefs?.edit()?.putBoolean("ai_asked:$userId", true)?.apply() ?: memory.add(userId)
    }
}
