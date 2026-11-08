package com.ansylab.vision

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * USB 转串口（CH340/CH341/CH343、CP210x、FTDI、PL2303 等）。
 *
 * 读数据走 SerialInputOutputManager 的后台线程，收到的字节先进队列，
 * Python 侧 `Serial.read()` 从队列里取 —— 和相机/IMU 一样是「Kotlin 收、Python 轮询」。
 */
object SerialPortManager {

    private const val ACTION_USB_PERMISSION = "com.ansylab.vision.USB_PERMISSION"

    private var manager: UsbManager? = null
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    /** 收到但还没被 Python 取走的字节。 */
    private val rx = ConcurrentLinkedQueue<Byte>()

    @Volatile
    var lastError: String = ""

    @Volatile
    var isOpen: Boolean = false
        private set

    @Volatile
    var currentName: String = ""

    @Volatile
    var bytesReceived: Long = 0
        private set

    @Volatile
    var bytesSent: Long = 0
        private set

    @Synchronized
    fun list(ctx: Context): String {
        val arr = JSONArray()
        try {
            val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
            for (d in drivers) {
                val o = JSONObject()
                o.put("name", describe(d))
                o.put("vendorId", d.device.vendorId)
                o.put("productId", d.device.productId)
                o.put("deviceName", d.device.deviceName)
                o.put("ports", d.ports.size)
                o.put("hasPermission", usb.hasPermission(d.device))
                arr.put(o)
            }
            // 没有匹配到已知驱动时，把原始 USB 设备也列出来，方便排查
            if (drivers.isEmpty()) {
                usb.deviceList.values.forEach { dev ->
                    val o = JSONObject()
                    o.put("name", "未知设备 %04x:%04x".format(dev.vendorId, dev.productId))
                    o.put("vendorId", dev.vendorId)
                    o.put("productId", dev.productId)
                    o.put("deviceName", dev.deviceName)
                    o.put("ports", 0)
                    o.put("hasPermission", usb.hasPermission(dev))
                    arr.put(o)
                }
            }
        } catch (t: Throwable) {
            lastError = "枚举串口失败: ${t.message}"
            PyBridge.log("[serial] $lastError")
        }
        return arr.toString()
    }

    private fun describe(d: UsbSerialDriver): String {
        val dev = d.device
        val chip = d.javaClass.simpleName
            .removeSuffix("SerialDriver")
            .removeSuffix("Driver")
        return "$chip %04x:%04x".format(dev.vendorId, dev.productId)
    }

    /**
     * 打开串口。index 是 list() 里的下标；baud 是波特率。
     *
     * parity：0=无 1=奇 2=偶 3=标记 4=空格
     * flow：0=无 1=RTS/CTS 2=DSR/DTR 4=XON/XOFF
     *
     * 返回空串表示成功。
     */
    @Synchronized
    fun open(
        ctx: Context, index: Int, baud: Int, dataBits: Int,
        stopBits: Int, parity: Int, flow: Int = 0
    ): String {
        close()
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        manager = usb

        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
        if (drivers.isEmpty()) return "没有找到可识别的 USB 串口设备"
        if (index < 0 || index >= drivers.size) return "串口下标越界（共 ${drivers.size} 个）"

        val driver = drivers[index]
        val device = driver.device
        if (!usb.hasPermission(device)) {
            requestPermission(ctx, device)
            return "需要 USB 权限：请在弹窗里允许后重试"
        }

        return try {
            val conn = usb.openDevice(device) ?: return "打开 USB 设备失败"
            val p = driver.ports.first()
            p.open(conn)
            p.setParameters(baud, dataBits, stopBits, parity)
            if (flow != 0) {
                runCatching { p.setFlowControl(flowMode(flow)) }
            }
            port = p
            currentName = describe(driver)
            isOpen = true
            bytesReceived = 0
            bytesSent = 0
            rx.clear()

            val io = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    data.forEach { rx.add(it) }
                    bytesReceived += data.size
                }

                override fun onRunError(e: Exception) {
                    lastError = "串口读线程异常: ${e.message}"
                    PyBridge.log("[serial] $lastError")
                }
            })
            ioManager = io
            io.start()
            lastError = ""
            PyBridge.log("[serial] 已打开 $currentName @$baud")
            ""
        } catch (t: Throwable) {
            lastError = "打开串口失败: ${t.message}"
            close()
            lastError
        }
    }

    private fun requestPermission(ctx: Context, device: UsbDevice) {
        val flags = PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName),
            flags
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == ACTION_USB_PERMISSION) {
                    try {
                        ctx.unregisterReceiver(this)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        // Android 13+ 动态注册广播必须显式声明导出性，这里用 NOT_EXPORTED
        // （系统发起的授权结果广播不受影响）。
        androidx.core.content.ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(ACTION_USB_PERMISSION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        (ctx.getSystemService(Context.USB_SERVICE) as UsbManager)
            .requestPermission(device, pi)
    }

    /** 往串口写数据；返回实际写入的字节数，失败返回 -1。 */
    @Synchronized
    fun write(data: ByteArray, timeoutMs: Int = 1000): Int {
        val p = port ?: run { lastError = "串口没打开"; return -1 }
        return try {
            p.write(data, timeoutMs)
            bytesSent += data.size
            data.size
        } catch (t: Throwable) {
            lastError = "写失败: ${t.message}"
            PyBridge.log("[serial] $lastError")
            -1
        }
    }

    /** 取走最多 max 个字节；没有数据返回空数组（不阻塞）。 */
    fun read(max: Int): ByteArray {
        if (max <= 0) return ByteArray(0)
        val out = ByteArray(max)
        var n = 0
        while (n < max) {
            val b = rx.poll() ?: break
            out[n++] = b
        }
        return if (n == 0) ByteArray(0) else out.copyOf(n)
    }

    /** 把整数映射到库里的枚举：1=RTS/CTS 2=DSR/DTR 4=XON/XOFF 5=内联 XON/XOFF。 */
    private fun flowMode(flow: Int): UsbSerialPort.FlowControl = when (flow) {
        1 -> UsbSerialPort.FlowControl.RTS_CTS
        2 -> UsbSerialPort.FlowControl.DTR_DSR
        4 -> UsbSerialPort.FlowControl.XON_XOFF
        5 -> UsbSerialPort.FlowControl.XON_XOFF_INLINE
        else -> UsbSerialPort.FlowControl.NONE
    }

    /** 队列里还有多少字节没取走。 */
    fun available(): Int = rx.size

    /** 控制 DTR / RTS 电平（部分设备用它们做复位或收发使能）。 */
    fun setDtr(on: Boolean): String {
        val p = port ?: return "串口没打开"
        return try {
            p.dtr = on
            ""
        } catch (t: Throwable) {
            "设置 DTR 失败: ${t.message}"
        }
    }

    fun setRts(on: Boolean): String {
        val p = port ?: return "串口没打开"
        return try {
            p.rts = on
            ""
        } catch (t: Throwable) {
            "设置 RTS 失败: ${t.message}"
        }
    }

    /** 读当前 DTR/RTS 状态，返回 "1,0" 这种（不可读时返回空串）。 */
    fun ctrlLines(): String {
        val p = port ?: return ""
        return try {
            "${if (p.dtr) 1 else 0},${if (p.rts) 1 else 0}"
        } catch (t: Throwable) {
            ""
        }
    }

    @Synchronized
    fun close() {
        try {
            ioManager?.stop()
        } catch (_: Throwable) {
        }
        ioManager = null
        try {
            port?.close()
        } catch (_: Throwable) {
        }
        port = null
        isOpen = false
        rx.clear()
    }
}
