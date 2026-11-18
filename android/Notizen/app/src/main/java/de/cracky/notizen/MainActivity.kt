package de.cracky.notizen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import de.cracky.notizen.ui.NotizenApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(245, 247, 250)
        window.navigationBarColor = android.graphics.Color.rgb(245, 247, 250)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF145CA8), onPrimary = Color.White,
                secondary = Color(0xFF326F9F), background = Color(0xFFF5F7FA),
                surface = Color.White, surfaceVariant = Color(0xFFEAF0F5),
                error = Color(0xFFB3261E)
            )) {
                NotizenApp()
            }
        }
    }
}
