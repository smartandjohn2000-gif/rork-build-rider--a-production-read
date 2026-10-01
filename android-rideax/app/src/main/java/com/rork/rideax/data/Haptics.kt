package com.rork.rideax.data

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Light tap, success and warning feedback, matching the iOS haptics. */
object Haptics {
    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun tap() = play(VibrationEffect.EFFECT_TICK, 10)

    fun success() = play(VibrationEffect.EFFECT_CLICK, 24)

    fun warning() = play(VibrationEffect.EFFECT_DOUBLE_CLICK, 40)

    private fun play(predefined: Int, fallbackMillis: Long) {
        val target = vibrator ?: return
        if (!target.hasVibrator()) return
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    target.vibrate(VibrationEffect.createPredefined(predefined))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    target.vibrate(VibrationEffect.createOneShot(fallbackMillis, VibrationEffect.DEFAULT_AMPLITUDE))
                else -> {
                    @Suppress("DEPRECATION")
                    target.vibrate(fallbackMillis)
                }
            }
        }
    }
}
