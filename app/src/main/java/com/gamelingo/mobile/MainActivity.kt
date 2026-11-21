package com.gamelingo.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.gamelingo.mobile.core.session.ResumeRequest
import com.gamelingo.mobile.ui.navigation.GameLingoAppRoot
import com.gamelingo.mobile.ui.theme.GameLingoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A tap on the "translation stopped - resume" notification lands here;
        // the home screen consumes the flag and re-asks capture consent.
        if (intent?.getBooleanExtra(ResumeRequest.EXTRA_AUTO_RESUME, false) == true) {
            ResumeRequest.onResumeNotificationTap()
        }
        // The identity is dark-only: always light system-bar icons over the dark background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            GameLingoTheme {
                GameLingoAppRoot()
            }
        }
    }
}
