package com.vlack.pdfview.sender

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vlack.pdfview.sender.ui.SenderRoute
import com.vlack.pdfview.sender.ui.theme.DocsViewSenderTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DocsViewSenderTheme {
                SenderRoute(viewModel)
            }
        }
    }
}
