package com.smartcart.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.smartcart.app.ui.AppNavHost
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.designsystem.SmartCartTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val pendingLink = MutableStateFlow<Uri?>(null)
    /** CR-006: the task a handoff link from a notification belongs to, carried in the intent (never in the URL). */
    private var fallbackTask: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptLink(intent)
        val container = (application as SmartCartApp).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                SmartCartTheme {
                    // Edge-to-edge: screens handle top/bottom insets themselves; side insets (landscape
                    // navigation bar, display cutouts) are applied once here so nothing — including the
                    // emergency Stop control — is drawn under a system bar.
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                    ) {
                        AppNavHost(pendingLink = pendingLink, consumeLink = { pendingLink.value = null; fallbackTask = null }, fallbackTask = { fallbackTask })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptLink(intent)
    }

    /** Only our own scheme is accepted; anything else is ignored. */
    private fun acceptLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "smartcart") return
        fallbackTask = intent.getStringExtra(EXTRA_FALLBACK_TASK)
        pendingLink.value = uri
    }

    companion object {
        const val EXTRA_FALLBACK_TASK = "com.smartcart.app.FALLBACK_TASK"
    }
}
