package de.timklge.karooroutegraph

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import de.timklge.karooroutegraph.screens.MainScreen
import de.timklge.karooroutegraph.theme.AppTheme
import org.koin.androidx.compose.KoinAndroidContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            KoinAndroidContext {
                AppTheme {
                    MainScreen {
                        finish()
                    }
                }
            }
        }
    }
}
