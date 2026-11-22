package com.smartcart.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptLink(intent)
        val container = (application as SmartCartApp).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                SmartCartTheme {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        AppNavHost(pendingLink = pendingLink, consumeLink = { pendingLink.value = null })
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
        if (uri.scheme == "smartcart") pendingLink.value = uri
    }
}
