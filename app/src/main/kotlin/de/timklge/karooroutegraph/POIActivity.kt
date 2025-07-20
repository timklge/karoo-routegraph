package de.timklge.karooroutegraph

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import de.timklge.karooroutegraph.screens.PoiScreen
import de.timklge.karooroutegraph.theme.AppTheme
import org.koin.compose.KoinContext

class POIActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            KoinContext {
                AppTheme {
                    PoiScreen() {
                        finish()
                    }
                }
            }
        }
    }
}