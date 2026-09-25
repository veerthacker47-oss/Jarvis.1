package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager

class AutomationManager(private val context: Context) {

    private val accessibility: JarvisAccessibilityService?
        get() = JarvisAccessibilityService.instance

    fun executeCommand(command: String) {
        val cmd = command.lowercase()
        when {
            cmd.contains("go home") -> accessibility?.triggerGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            cmd.contains("go back") -> accessibility?.triggerGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            cmd.contains("open notifications") -> accessibility?.triggerGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            cmd.contains("take screenshot") -> accessibility?.triggerGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            cmd.contains("turn on flashlight") -> setFlashlight(true)
            cmd.contains("turn off flashlight") -> setFlashlight(false)
            cmd.startsWith("open ") -> openAppByName(cmd.removePrefix("open ").trim())
        }
    }

    private fun setFlashlight(enabled: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enabled)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openAppByName(appName: String) {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)
        for (app in packages) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label == appName.lowercase()) {
                val intent = pm.getLaunchIntentForPackage(app.packageName)
                intent?.let { context.startActivity(it) }
                return
            }
        }
    }
}
