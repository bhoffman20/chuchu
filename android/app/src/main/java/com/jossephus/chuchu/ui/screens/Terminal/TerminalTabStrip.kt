package com.jossephus.chuchu.ui.screens.Terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.service.terminal.SessionStatus
import com.jossephus.chuchu.service.terminal.TabSession
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColorPalette
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

/**
 * Status color for a session status dot.
 */
private fun statusColor(status: SessionStatus, colors: ChuColorPalette): Color = when (status) {
    SessionStatus.Connected -> colors.success
    SessionStatus.Connecting,
    SessionStatus.Reconnecting -> colors.accent
    SessionStatus.Disconnected,
    SessionStatus.Error -> colors.error
}

/**
 * Human-readable status text for accessibility.
 */
internal fun statusLabel(status: SessionStatus): String = when (status) {
    SessionStatus.Connected -> "connected"
    SessionStatus.Connecting -> "connecting"
    SessionStatus.Reconnecting -> "reconnecting"
    SessionStatus.Disconnected -> "disconnected"
    SessionStatus.Error -> "error"
}

/**
 * Always-visible global top tab strip for strip mode.
 *
 * Shows all active terminal sessions, even with one tab.
 * Each clickable target respects the Android 48dp accessibility minimum.
 */
@Composable
fun TerminalTabStrip(
    tabs: List<TabSession>,
    activeTabId: String?,
    onTabSelected: (String) -> Unit,
    onAddTab: () -> Unit,
    onOpenManager: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(colors.surfaceVariant)
            .horizontalScroll(scrollState)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tabs.forEach { tab ->
            val isActive = tab.id == activeTabId
            val sessionState by tab.sessionState.collectAsStateWithLifecycle()
            val status = sessionState.status
            val label = tab.spec.tabLabel
            val statusText = statusLabel(status)
            val contentDesc = "$statusText — $label"

            Box(
                modifier = Modifier
                    .semantics { contentDescription = contentDesc }
                    .defaultMinSize(minHeight = 48.dp)
                    .widthIn(min = 48.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .background(
                        if (isActive) colors.accent.copy(alpha = 0.15f)
                        else Color.Transparent
                    )
                    .clickable { onTabSelected(tab.id) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Status dot
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusColor(status, colors))
                    )
                    // Host/profile label
                    ChuText(
                        text = label,
                        style = typography.labelSmall,
                        color = if (isActive) colors.accent else colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (tabs.isNotEmpty()) {
            Spacer(modifier = Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(16.dp)
                    .background(colors.border.copy(alpha = 0.4f))
            )
            Spacer(modifier = Modifier.width(2.dp))
        }

        // + button — opens the in-terminal server picker
        ChuButton(
            onClick = onAddTab,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
            variant = ChuButtonVariant.Ghost,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 10.dp,
                vertical = 4.dp,
            ),
            contentDescription = "new connection",
        ) {
            ChuText("+", style = typography.label, color = colors.accent)
        }

        // Overflow/all-tabs button — opens the expanded tab manager
        if (tabs.size > 1) {
            ChuButton(
                onClick = onOpenManager,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
                variant = ChuButtonVariant.Ghost,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp,
                    vertical = 4.dp,
                ),
                contentDescription = "all tabs",
            ) {
                ChuText(
                    if (tabs.size > 99) "≡" else "${tabs.size}",
                    style = typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))
    }
}
