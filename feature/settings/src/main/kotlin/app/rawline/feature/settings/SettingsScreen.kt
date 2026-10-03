package app.rawline.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rawline.core.nativelib.Native

@Composable
fun SettingsScreen(versionName: String, buildNumber: Int, buildDate: String, modifier: Modifier = Modifier) {
    val libraw = runCatching { Native.librawVersion() }.getOrElse { "failed: ${it.message}" }
    Column(modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Row("Version", "$versionName (build $buildNumber)")
        Row("Built", buildDate)
        Row("LibRaw", libraw)
    }
}

@Composable
private fun Row(label: String, value: String) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
