package com.ansylab.vision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 姿态传感器采集。
 *
 * 思路和相机一致：Kotlin 侧注册监听、把最新值写进 volatile 数组，
 * Python 侧轮询读取（不回调，避免跨语言线程问题）。
 *
 * 支持的类型见 [TYPES]：
 *   accel   加速度（含重力，m/s²）
 *   gyro    角速度（rad/s）
 *   mag     地磁（μT）
 *   gravity 重力分量（m/s²）
 *   linear  线性加速度（去重力，m/s²）
 *   rotation  旋转矢量（含地磁，带绝对航向）
 *   game      游戏旋转矢量（不含地磁，推荐做平衡类控制）
 *   geomagnetic 地磁旋转矢量（不用陀螺）
 */
object ImuSensor {

    /** 逻辑名 -> Sensor.TYPE_xxx */
    val TYPES: Map<String, Int> = linkedMapOf(
        "accel" to Sensor.TYPE_ACCELEROMETER,
        "gyro" to Sensor.TYPE_GYROSCOPE,
        "mag" to Sensor.TYPE_MAGNETIC_FIELD,
        "gravity" to Sensor.TYPE_GRAVITY,
        "linear" to Sensor.TYPE_LINEAR_ACCELERATION,
        "rotation" to Sensor.TYPE_ROTATION_VECTOR,
        "game" to Sensor.TYPE_GAME_ROTATION_VECTOR,
        "geomagnetic" to Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
    )

    private var manager: SensorManager? = null
    private var listener: SensorEventListener? = null

    /** 逻辑名 -> 最新原始值。数组整体替换，读的时候不会读到半截。 */
    private val values = HashMap<String, FloatArray>()

    /** 逻辑名 -> 精度（SENSOR_STATUS_xxx）。 */
    private val accuracy = HashMap<String, Int>()

    @Volatile
    private var rateHz: Int = 50

    @Volatile
    var lastError: String = ""

    /** 当前是否在采集。 */
    @Volatile
    var running: Boolean = false
        private set

    private val lock = Any()

    @Synchronized
    fun start(ctx: Context, kinds: List<String>, hz: Int): String {
        stop()
        val sm = ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return "没有 SensorManager"
        manager = sm
        rateHz = hz.coerceIn(1, 400)

        val missing = ArrayList<String>()
        val registered = ArrayList<Sensor>()

        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val key = keyOf(event.sensor.type) ?: return
                synchronized(lock) {
                    values[key] = event.values.copyOf()
                    accuracy[key] = event.accuracy
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                val key = keyOf(sensor?.type ?: return) ?: return
                synchronized(lock) { this@ImuSensor.accuracy[key] = accuracy }
            }
        }
        listener = l

        for (k in kinds) {
            val type = TYPES[k] ?: run { missing.add("$k(未知)"); null } ?: continue
            val sensor = sm.getDefaultSensor(type)
            if (sensor == null) {
                missing.add(k)
                continue
            }
            val periodUs = (1_000_000 / rateHz).coerceAtLeast(1000)
            val ok = sm.registerListener(l, sensor, periodUs)
            if (ok) registered.add(sensor) else missing.add("$k(注册失败)")
        }

        running = registered.isNotEmpty()
        lastError = if (missing.isEmpty()) "" else "不可用: " + missing.joinToString(",")
        if (!running) return "没有可用的传感器: " + missing.joinToString(",")
        return ""
    }

    @Synchronized
    fun stop() {
        val sm = manager
        val l = listener
        if (sm != null && l != null) {
            runCatching { sm.unregisterListener(l) }
        }
        listener = null
        running = false
        synchronized(lock) {
            values.clear()
            accuracy.clear()
        }
    }

    private fun keyOf(type: Int): String? =
        TYPES.entries.firstOrNull { it.value == type }?.key

    /** 原始值；没有数据时返回空数组。 */
    fun value(kind: String): FloatArray = synchronized(lock) {
        values[kind]?.copyOf() ?: FloatArray(0)
    }

    fun accuracyOf(kind: String): Int = synchronized(lock) { accuracy[kind] ?: 0 }

    /**
     * 欧拉角（度）：[方位角, 俯仰角, 横滚角]。
     *
     * 用 rotation（带地磁）优先，没有就退回 game（方位角相对上电姿态）。
     * 返回空数组表示没有旋转矢量数据。
     */
    fun euler(): FloatArray {
        val hasRotation = value("rotation").size >= 4
        val q = if (hasRotation) value("rotation") else value("game")
        if (q.size < 4) return FloatArray(0)

        val rot = FloatArray(9)
        // 注意：getRotationMatrixFromVector 要的就是传感器原始值 [x, y, z, w]，
        // 不要在这里重排成 [w, x, y, z]（重排后平放时 pitch 会变成 -90°）。
        SensorManager.getRotationMatrixFromVector(rot, q)
        val angles = FloatArray(3)
        SensorManager.getOrientation(rot, angles)
        return floatArrayOf(
            Math.toDegrees(angles[0].toDouble()).toFloat(),
            Math.toDegrees(angles[1].toDouble()).toFloat(),
            Math.toDegrees(angles[2].toDouble()).toFloat()
        )
    }

    /** 旋转矩阵（3x3 行优先），供需要自己算方向的场景用。 */
    fun rotationMatrix(): FloatArray {
        val q = if (value("rotation").size >= 4) value("rotation") else value("game")
        if (q.size < 4) return FloatArray(0)
        val rot = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rot, q)
        return rot
    }

    /** 列出本机可用的传感器（设置页/脚本自检用）。 */
    fun available(ctx: Context): String {
        val sm = ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return "没有 SensorManager"
        val parts = ArrayList<String>()
        for ((key, type) in TYPES) {
            val s = sm.getDefaultSensor(type)
            if (s != null) {
                val hz = if (s.minDelay > 0) 1_000_000 / s.minDelay else 0
                parts.add("$key=${s.name}@${hz}Hz")
            }
        }
        return parts.joinToString("\n")
    }
}
