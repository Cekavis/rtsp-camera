package com.cekavis.rtspcamera.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import com.cekavis.rtspcamera.model.AppController
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun CameraPreview(controller: AppController, modifier: Modifier = Modifier) {
    val active = remember(controller) { AtomicBoolean(true) }
    DisposableEffect(controller) {
        active.set(true)
        onDispose {
            active.set(false)
            controller.detachPreview()
        }
    }
    AndroidView(
        modifier = modifier.semantics { contentDescription = "本机相机预览" },
        factory = { context ->
            SurfaceView(context).apply {
                // Expose the label on the Compose node, including while the view is clipped.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        if (active.get() && holder.surface.isValid && width > 0 && height > 0) {
                            controller.attachPreview(holder.surface, width, height)
                        }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        controller.detachPreview()
                    }
                })
            }
        },
    )
}
