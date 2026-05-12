// Single-activity host. Phase 1 placeholder body — the customer + attendant UI
// is rebuilt in Phases 2–4 against docs/Strict design screens/.
package app.balancee.smartpump.display

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartPumpDisplayTheme {
                PhaseOnePlaceholder()
            }
        }
    }
}

@Composable
private fun PhaseOnePlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "SmartPump Display — rebuild in progress",
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
