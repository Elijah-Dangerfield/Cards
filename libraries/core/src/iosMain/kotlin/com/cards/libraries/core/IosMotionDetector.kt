package com.dangerfield.cards.libraries.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import me.tatarka.inject.annotations.Inject
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSDate
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTimeInterval
import platform.Foundation.timeIntervalSince1970
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.math.sqrt

@OptIn(ExperimentalForeignApi::class)
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosMotionDetector : MotionDetector {
    
    private val motionManager = CMMotionManager()
    private val motionChannel = Channel<MotionUpdate>(Channel.BUFFERED)
    override val motionUpdates: Flow<MotionUpdate> = motionChannel.receiveAsFlow()
    
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastZ = 0.0
    private var isFirstReading = true
    
    override fun start() {
        if (!motionManager.accelerometerAvailable) return
        
        isFirstReading = true
        motionManager.accelerometerUpdateInterval = UPDATE_INTERVAL
        motionManager.startAccelerometerUpdatesToQueue(
            NSOperationQueue.mainQueue
        ) { data, _ ->
            data?.let { accelerometerData ->
                val acceleration = accelerometerData.acceleration
                acceleration.useContents {
                    processAcceleration(x, y, z)
                }
            }
        }
    }
    
    override fun stop() {
        motionManager.stopAccelerometerUpdates()
    }
    
    private fun processAcceleration(x: Double, y: Double, z: Double) {
        if (isFirstReading) {
            lastX = x
            lastY = y
            lastZ = z
            isFirstReading = false
            return
        }
        
        val currentTime = (NSDate().timeIntervalSince1970 * 1000).toLong()
        
        val deltaX = x - lastX
        val deltaY = y - lastY
        val deltaZ = z - lastZ
        
        lastX = x
        lastY = y
        lastZ = z
        
        val magnitude = sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ).toFloat()
        
        motionChannel.trySend(MotionUpdate(currentTime, magnitude))
    }
    
    companion object {
        private const val UPDATE_INTERVAL: NSTimeInterval = 0.05
    }
}
