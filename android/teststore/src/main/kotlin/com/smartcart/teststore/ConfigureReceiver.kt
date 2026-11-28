package com.smartcart.teststore

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Sets the scenario for the next run and empties the shop. Test use only.
 *
 * A receiver rather than an activity: `am broadcast` returns once [onReceive] has finished, whereas
 * `am start -W` on a translucent activity that finishes at once never returns on API 26.
 */
class ConfigureReceiver : BroadcastReceiver() {
    @SuppressLint("ApplySharedPref") // saved before `am broadcast` returns, so the next launch sees it
    override fun onReceive(context: Context, intent: Intent) {
        val scenario = intent.getStringExtra("scenario") ?: "normal"
        context.getSharedPreferences(StoreActivity.PREFS, Context.MODE_PRIVATE).edit().putString("scenario", scenario).commit()
        StoreActivity.State.reset()
    }
}
