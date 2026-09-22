package com.cekavis.rtspcamera.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cekavis.rtspcamera.CameraApplication

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = (application as CameraApplication).controller
        setContent {
            CameraTheme {
                CameraApp(controller = controller, activity = this)
            }
        }
    }
}
