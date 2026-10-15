package com.lemon.shotcoach.analysis.analyzer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.concurrent.atomic.AtomicReference

/**
 * 水平检测 · 传感器通道（FR-4）。快通道，~50Hz，成本 ≈ 0ms。
 *
 * 见 docs/ALGORITHMS.md §3.1：
 *  TYPE_ROTATION_VECTOR → getRotationMatrixFromVector → getOrientation → 绕 Z 轴角。
 * 无 TYPE_ROTATION_VECTOR 的设备回退 TYPE_ACCELEROMETER。
 *
 * 结果写 [AtomicReference]，由分析线程无锁读取（跨线程读的是最新一帧，不需要锁）。
 */
class OrientationAnalyzer(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val roll = AtomicReference(0f)

    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    fun start() {
        sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** 当前绕 Z 轴倾角（度）。正 = 顺时针 */
    fun rollDegrees(): Float = roll.get()

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                // orientation[2] = 绕 Z 轴（azimuth）；这里取绕屏幕法线的倾角
                roll.set(Math.toDegrees(orientation[2].toDouble()).toFloat())
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // 回退：由重力向量估算屏幕倾角
                val gx = event.values[0]
                val gy = event.values[1]
                roll.set(Math.toDegrees(kotlin.math.atan2(-gx.toDouble(), gy.toDouble())).toFloat())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
