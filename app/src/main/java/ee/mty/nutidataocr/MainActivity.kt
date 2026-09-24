package ee.mty.nutidataocr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import ee.mty.nutidataocr.ui.theme.NutidataOCRTheme

class MainActivity : ComponentActivity() {
    private val ocrViewModel by viewModels<OcrViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NutidataOCRTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    OcrScreen(
                        model = ocrViewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}
