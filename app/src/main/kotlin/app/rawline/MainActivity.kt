package app.rawline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.rawline.core.ui.RawlineTheme
import app.rawline.feature.settings.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RawlineTheme { RawlineApp() } }
    }
}

@Composable
private fun RawlineApp() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") { Home(onSettings = { nav.navigate("settings") }) }
        composable("settings") {
            SettingsScreen(BuildConfig.VERSION_NAME, BuildConfig.BUILD_NUMBER, BuildConfig.BUILD_DATE)
        }
    }
}

@Composable
private fun Home(onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Text("Rawline", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Library arrives in the next milestone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSettings) { Text("Settings") }
    }
}
