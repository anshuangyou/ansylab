package com.ansylab.vision

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 相机枚举：用 Camera2 的 CameraManager 直接读，不需要先 bind。
 *
 * 返回的是 JSON 字符串数组，字段：
 *   id           Camera2 的 camera id（"0"、"1"...）
 *   facing       "back" / "front" / "external"
 *   focal        焦距（mm，取第一个）
 *   orientation  传感器安装方向（0/90/180/270）
 *   flash        是否有闪光灯
 *   level        INFO_SUPPORTED_HARDWARE_LEVEL（0=limited 1=full 2=legacy 3=3）
 *   maxSize      最大输出尺寸（宽x高）
 */
object CameraCatalog {

    fun listJson(ctx: Context): String {
        val out = JSONArray()
        try {
            val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                val o = JSONObject()
                o.put("id", id)
                o.put("facing", facingName(c.get(CameraCharacteristics.LENS_FACING)))
                val fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                o.put("focal", fl?.firstOrNull()?.toDouble() ?: 0.0)
                o.put(
                    "orientation",
                    c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                )
                o.put(
                    "flash",
                    c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                )
                o.put(
                    "level",
                    c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
                )
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                val biggest = sizes?.maxByOrNull { it.width.toLong() * it.height } ?: null
                o.put(
                    "maxSize",
                    if (biggest != null) "${biggest.width}x${biggest.height}" else ""
                )
                // 设备支持的 AE 目标帧率范围，形如 "15-30,30-30"
                o.put(
                    "fpsRanges",
                    c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        ?.joinToString(",") { "${it.lower}-${it.upper}" } ?: ""
                )
                out.put(o)
            }
        } catch (t: Throwable) {
            PyBridge.log("[camera] 枚举失败: $t")
        }
        return out.toString()
    }

    private fun facingName(v: Int?): String = when (v) {
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        else -> "external"
    }

    /** 当前正在使用的 camera id（闪光灯要用）。 */
    @Volatile
    var currentId: String? = null

    /** 直接开/关闪光灯（手电筒模式），不依赖相机是否在预览。 */
    fun setTorch(ctx: Context, on: Boolean): String {
        val id = currentId ?: return "还没有打开相机，无法控制闪光灯"
        return try {
            val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val c = manager.getCameraCharacteristics(id)
            val has = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!has) return "相机 $id 没有闪光灯"
            manager.setTorchMode(id, on)
            ""
        } catch (t: Throwable) {
            "闪光灯切换失败: ${t.message}"
        }
    }

    fun torchAvailable(ctx: Context): Boolean {
        val id = currentId ?: return false
        return try {
            val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } catch (t: Throwable) {
            false
        }
    }
}
