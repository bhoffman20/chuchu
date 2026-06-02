package com.jossephus.chuchu.ui.screens.Terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.repository.HostRepository
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.service.terminal.TabSpec
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Build a [TabSpec] from a [HostProfile].
 */
internal fun buildTabSpecFromHost(
    host: HostProfile,
    keyPassphrase: String = "",
): TabSpec = TabSpec(
    hostId = host.id,
    displayName = host.name,
    host = host.host,
    port = host.port,
    username = host.username,
    password = host.password,
    authMethod = host.authMethod,
    keyPassphrase = keyPassphrase,
    transport = host.transport,
    postConnectCommand = host.postConnectCommand,
)

/**
 * In-terminal server picker for the new tab-strip mode.
 *
 * Shows existing saved hosts.  Selecting a host builds a [TabSpec] and calls
 * [onHostSelected].  The parent is responsible for the passphrase gate before
 * opening the tab.  Dismisses immediately on host selection so dialogs do not
 * stack.
 */
@Composable
fun TerminalServerPicker(
    visible: Boolean,
    onHostSelected: (TabSpec) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val context = LocalContext.current
    var hosts by remember { mutableStateOf<List<HostProfile>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        loaded = false
        val db = AppDatabase.getInstance(context)
        val hostRepo = HostRepository(db.hostProfileDao())
        hostRepo.observeAll().collect { allHosts ->
            hosts = allHosts
            loaded = true
        }
    }

    if (!visible) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.85f))
            .semantics { contentDescription = "close server picker" }
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(colors.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChuText("connect to server", style = typography.headline)
                ChuButton(
                    onClick = onDismiss,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
                    variant = ChuButtonVariant.Ghost,
                    bracketed = true,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    contentDescription = "close",
                ) {
                    ChuText("x", style = typography.label, color = colors.textMuted)
                }
            }

            if (!loaded) {
                ChuText("loading hosts\u2026", style = typography.body, color = colors.textMuted)
            } else if (hosts.isEmpty()) {
                ChuText("no saved hosts.", style = typography.body, color = colors.textMuted)
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 320.dp),
                ) {
                    items(hosts) { host ->
                        ServerPickerRow(
                            host = host,
                            onClick = {
                                val spec = buildTabSpecFromHost(host)
                                onHostSelected(spec)
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            ChuText(
                "choose a host to start a new session.",
                style = typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun ServerPickerRow(
    host: HostProfile,
    onClick: () -> Unit,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val credentials = "${host.username}@${host.host}:${host.port}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "${host.name}, $credentials"
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ChuText(host.name, style = typography.label, color = colors.textPrimary)
            ChuText(
                credentials,
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}
