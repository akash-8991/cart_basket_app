package com.smartcart.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.smartcart.designsystem.DangerButton
import com.smartcart.designsystem.Spacing
import com.smartcart.model.CartTask
import com.smartcart.model.TaskStatus

/**
 * Global automation status with an always-visible emergency Stop (PRD v1.4 §4: "visible emergency
 * stop"; FR-2: "visible automation status"). Shown on every screen while a task is active.
 */
@Composable
fun ActiveTaskBar(task: CartTask?, onOpen: (String) -> Unit, onStop: (String) -> Unit) {
    AnimatedVisibility(visible = task != null) {
        if (task == null) return@AnimatedVisibility
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .clickable { onOpen(task.taskId) }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.APPROVED) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.inverseOnSurface)
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(
                    "${task.provider.displayName}: ${task.status.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                DangerButton(
                    "Stop",
                    onClick = { onStop(task.taskId) },
                    modifier = Modifier.semantics { contentDescription = "Emergency stop automation" },
                )
            }
        }
    }
}
