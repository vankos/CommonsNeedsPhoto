package com.example.commonsneedsphoto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.commonsneedsphoto.ui.MainViewModel
import com.example.commonsneedsphoto.ui.MapScreen
import com.example.commonsneedsphoto.ui.theme.CommonsNeedsPhotoTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CommonsNeedsPhotoTheme {
                val viewModel: MainViewModel = viewModel()
                MapScreen(viewModel)
            }
        }
    }
}
