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
@ContributesBinding(AppScope::class, boundType = MotionDetector::class)
class AndroidMotionDetector(
    private val context: Context,
) : MotionDetector, SensorEventListener {
    
    private val sensorManager by lazy { 
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager 
    }
    private val accelerometer by lazy { 
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) 
    }
    
    private val motionChannel = Channel<MotionUpdate>(Channel.BUFFERED)
    override val motionUpdates: Flow<MotionUpdate> = motionChannel.receiveAsFlow()
    
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastUpdateTime = 0L
    private var isFirstReading = true
    
    override fun start() {
        isFirstReading = true
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
        
        if (timeDiff < SAMPLE_INTERVAL_MS) return
        
        lastUpdateTime = currentTime
        
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        
        if (isFirstReading) {
            lastX = x
            lastY = y
            lastZ = z
            isFirstReading = false
            return
        }
        
        val deltaX = x - lastX
        val deltaY = y - lastY
        val deltaZ = z - lastZ
        
        lastX = x
        lastY = y
        lastZ = z
        
        val magnitude = sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)
        
        motionChannel.trySend(MotionUpdate(currentTime, magnitude))
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    companion object {
        private const val SAMPLE_INTERVAL_MS = 50
    }
}
