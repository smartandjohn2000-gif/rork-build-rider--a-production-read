package com.rork.rideax

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rork.rideax.ui.navigation.RideAXApp
import com.rork.rideax.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as RideAXApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        container.auth.handleRedirect(intent?.data)
        setContent {
            AppTheme {
                RideAXApp(container)
            }
        }
    }

    /** Rork Auth redirects back here after Apple / Google sign-in. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        container.auth.handleRedirect(intent.data)
    }

    override fun onResume() {
        super.onResume()
        container.auth.onHostResumed()
    }
}
