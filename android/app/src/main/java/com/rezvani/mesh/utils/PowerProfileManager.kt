package com.rezvani.mesh.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.rezvani.mesh.PowerState

object PowerProfileManager {

    fun applyPowerState(activity: Activity?, state: PowerState) {
        applyBrightness(activity, state)
        cancelVibrations(activity)
    }

    fun applyBrightness(activity: Activity?, state: PowerState) {
        val brightness = when (state) {
            PowerState.EMERGENCY,
            PowerState.ACTIVE -> -1f
            PowerState.BALANCED -> 0.8f
            PowerState.POWER_SAVER -> 0.5f
            PowerState.MINIMAL,
            PowerState.HIBERNATION,
            PowerState.DEAD -> 0.25f
        }
        activity?.window?.attributes = activity?.window?.attributes?.apply {
            screenBrightness = brightness
        }
    }

    fun cancelVibrations(context: Context?) {
        val vibrator = context?.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        vibrator?.cancel()
    }

    fun openBatterySaverSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
        // App (non-Activity) context requires NEW_TASK; the ViewModel passes
        // applicationContext, so without this flag startActivity() throws.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        }
    }
}
