package com.cekavis.rtspcamera

import android.app.Application
import com.cekavis.rtspcamera.service.AppGraph

class CameraApplication : Application() {
    val controller: AppGraph by lazy { AppGraph(this) }
    override fun onCreate() { super.onCreate(); controller }
}
