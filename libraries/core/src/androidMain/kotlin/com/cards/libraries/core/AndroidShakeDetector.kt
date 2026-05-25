package com.dangerfield.cards.libraries.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.math.sqrt

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = ShakeDetector::class)
class AndroidShakeDetector(
    private val context: Context,
) : ShakeDetector, SensorEventListener {
    
    private val sensorManager by lazy { 
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager 
    }
    private val accelerometer by lazy { 
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) 
    }
    
    private val shakeChannel = Channel<ShakeEvent>(Channel.BUFFERED)
    override val shakeEvents: Flow<ShakeEvent> = shakeChannel.receiveAsFlow()
    
    private var lastShakeTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastUpdateTime = 0L
    
    override fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }
    
    override fun stop() {
        sensorManager.unregisterListener(this)
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - lastUpdateTime
        
        if (timeDiff < SHAKE_SAMPLE_INTERVAL_MS) return
        
        lastUpdateTime = currentTime
        
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        
        val deltaX = x - lastX
        val deltaY = y - lastY
        val deltaZ = z - lastZ
        
        lastX = x
        lastY = y
        lastZ = z
        
        val acceleration = sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ) / timeDiff * 10000
        
        if (acceleration > SHAKE_THRESHOLD) {
            if (currentTime - lastShakeTime > SHAKE_COOLDOWN_MS) {
                lastShakeTime = currentTime
                
                val intensity = when {
                    acceleration > VIGOROUS_THRESHOLD -> ShakeIntensity.VIGOROUS
                    acceleration > NORMAL_THRESHOLD -> ShakeIntensity.NORMAL
                    else -> ShakeIntensity.GENTLE
                }
                
                shakeChannel.trySend(ShakeEvent(currentTime, intensity))
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }
    
    companion object {
        // Raised after both platforms were tripping on normal pocket /
        // hand motion. Math here is normalized acceleration in
        // velocity-derivative units (Δposition / Δtime × 10000) — not
        // m/s² directly — so the absolute number isn't directly
        // comparable to physical units. These values are tuned empirically:
        // SHAKE = "definitely a shake gesture", NORMAL = "intentional
        // shake", VIGOROUS = "they really meant it". Each ~3× the prior
        // value; SHAKE_THRESHOLD * 1.5 ≈ NORMAL, NORMAL * 1.5 ≈ VIGOROUS.
        private const val SHAKE_THRESHOLD = 2500
        private const val NORMAL_THRESHOLD = 3800
        private const val VIGOROUS_THRESHOLD = 5500
        private const val SHAKE_COOLDOWN_MS = 1500L
        private const val SHAKE_SAMPLE_INTERVAL_MS = 100
    }
}
