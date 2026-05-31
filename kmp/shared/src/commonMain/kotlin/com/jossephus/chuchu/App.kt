package com.jossephus.chuchu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.ui.navigation.AppRoute
import com.jossephus.chuchu.ui.navigation.AppShellState

@Composable
fun App() {
    val shellState = remember { AppShellState() }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text = "chuchu", style = MaterialTheme.typography.headlineMedium)
                Text(text = "KMP migration shell", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    RouteButton("Servers", shellState.route == AppRoute.Servers) { shellState.navigateTo(AppRoute.Servers) }
                    RouteButton("Terminal", shellState.route == AppRoute.Terminal) { shellState.navigateTo(AppRoute.Terminal) }
                    RouteButton("Settings", shellState.route == AppRoute.Settings) { shellState.navigateTo(AppRoute.Settings) }
                }
                ScreenPlaceholder(shellState.route)
            }
        }
    }
}

@Composable
private fun RouteButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(label)
    }
}

@Composable
private fun ScreenPlaceholder(route: AppRoute) {
    val title = when (route) {
        AppRoute.Servers -> "Server list shell"
        AppRoute.Terminal -> "Terminal shell"
        AppRoute.Settings -> "Settings shell"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title)
            Text("Shared navigation and theme are now wired in kmp/shared.")
        }
    }
}
