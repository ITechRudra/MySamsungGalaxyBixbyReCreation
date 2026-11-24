package com.bixi.agent.core

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

/** Flashlight control. Needs no camera permission. */
object Torch {
    private var manager: CameraManager? = null
    private var cameraId: String? = null

    @Volatile
    var isOn = false
        private set

    fun init(context: Context) {
        try {
            val cm = context.getSystemService(CameraManager::class.java) ?: return
            manager = cm
            cameraId = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(id: String, enabled: Boolean) {
                    if (id == cameraId) isOn = enabled
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
        }
    }

    fun set(on: Boolean): Boolean = try {
        val id = cameraId ?: error("no flash")
        manager?.setTorchMode(id, on)
        isOn = on
        true
    } catch (_: Exception) {
        false
    }
}
