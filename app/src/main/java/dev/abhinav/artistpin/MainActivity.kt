package dev.abhinav.artistpin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.abhinav.artistpin.navigation.ArtistPinRoot
import dev.abhinav.artistpin.ui.theme.ArtistPinTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArtistPinTheme {
                ArtistPinRoot()
            }
        }
    }
}
