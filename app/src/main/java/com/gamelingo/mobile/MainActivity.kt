package com.gamelingo.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gamelingo.mobile.ui.navigation.GameLingoAppRoot
import com.gamelingo.mobile.ui.theme.GameLingoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GameLingoTheme {
                GameLingoAppRoot()
            }
        }
    }
}
