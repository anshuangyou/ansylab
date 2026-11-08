package com.ansylab.vision

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * 与 libansylab.so 的 JNI 接口。
 *
 * 设计要点：
 *  - 相机帧**借用** ImageProxy：Python 读的就是相机自己的 buffer，
 *    读完（releaseFrame）才 close，全程不拷贝像素。
 *  - Python 通过 [acquireFrame] / [framePtr] 拿到地址，用 ctypes 在该地址上
 *    建 numpy 视图（必须走 JNI 取指针：AGP 默认 extractNativeLibs=false，
 *    库没有解压到磁盘，Python 侧无法 dlopen 打开 .so）。
 *  - 显示走 AHardwareBuffer 双缓冲，Python 用 cv2 的 dst 参数直接画进去。
 */
object NativeCamera {

    /**
     * 相机线程调用：把这一帧借给 native，不拷贝像素。
     *
     * 调用方**不要**再 close 这个 ImageProxy —— native 会在 Python 读完
     * （[releaseFrame]）或丢帧时负责 close。
     */
    @JvmStatic
    external fun pushFrame(
        image: ImageProxy,
        buffer: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int,
        rotationDegrees: Int
    )

    /** 相机帧需要的旋转角（CameraX 已转正时为 0）。 */
    @JvmStatic
    external fun rotationDegrees(): Int

    /** 占住最新一帧，返回 slot 号；没有可用帧时返回 -1。用完必须 [releaseFrame]。 */
    @JvmStatic
    external fun acquireFrame(): Int

    /** 归还 slot：关闭 ImageProxy 把 buffer 还给相机。 */
    @JvmStatic
    external fun releaseFrame(slot: Int)

    /** slot 对应的 RGBA 首地址，交给 Python 建 numpy 视图。 */
    @JvmStatic
    external fun framePtr(slot: Int): Long

    /** 相机帧的宽高。 */
    @JvmStatic
    external fun frameWidth(): Int

    @JvmStatic
    external fun frameHeight(): Int

    /** 相机帧的行字节数（真机上常大于 width*4，Python 建 view 时必须用）。 */
    @JvmStatic
    external fun frameRowStride(): Int

    // ------------------------------------------------------------ 模型输入缓冲
    //
    // Python 把 numpy 视图直接建在这块 native 内存上，cv2 写完后 Kotlin 从同一块
    // 内存建 ORT tensor，避免每帧 4.9 MB 的 tobytes() + Chaquopy 拷贝。

    /** 分配/复用 float32 输入缓冲，返回首地址（0 = 失败）。 */
    @JvmStatic
    external fun allocInput(floatCount: Int): Long

    /** 输入缓冲的 direct ByteBuffer 视图。 */
    @JvmStatic
    external fun inputBuffer(floatCount: Int): ByteBuffer?

    /** 确保显示缓冲是 w*h*4，返回首地址（软件显示路径用）。 */
    @JvmStatic
    external fun displayResize(width: Int, height: Int): Long

    /** 显示序号 +1（软件显示路径用）。 */
    @JvmStatic
    external fun markPresented()

    // ---------------------------------------------------------------- 硬件显示
    //
    // AHardwareBuffer 双缓冲：Python 直接写进 GPU 会采样的内存，
    // Bitmap 用 wrapHardwareBuffer 包一层，省掉每帧 copyPixelsFromBuffer 的 3 MB 拷贝。

    /** 绑定两块 AHardwareBuffer；返回 false 表示硬件显示不可用，需要退回软件路径。 */
    @JvmStatic
    external fun setDisplayBuffers(
        buffer0: android.hardware.HardwareBuffer,
        buffer1: android.hardware.HardwareBuffer,
        width: Int,
        height: Int
    ): Boolean

    /** 锁住后台缓冲，返回可写首地址（0 表示失败）。 */
    @JvmStatic
    external fun beginDraw(): Long

    /** 解锁并交换前后台。 */
    @JvmStatic
    external fun endDraw()

    /** 当前该显示的缓冲下标（0/1）。 */
    @JvmStatic
    external fun frontIndex(): Int

    @JvmStatic
    external fun hasHardwareDisplay(): Boolean

    /** 显示缓冲的 direct ByteBuffer 视图（native 内存，不额外分配）。 */
    @JvmStatic
    external fun displayBuffer(): ByteBuffer?

    @JvmStatic
    external fun displayWidth(): Int

    @JvmStatic
    external fun displayHeight(): Int

    @JvmStatic
    external fun displaySeq(): Long

    /** 被丢弃的帧数：三个槽都被 Python 占住时才会增加。 */
    @JvmStatic
    external fun droppedFrames(): Long

    @JvmStatic
    external fun frameSeq(): Long

    /** 哪些帧槽仍被占用（bit i = slot i），排查帧池问题时用。 */
    @JvmStatic
    external fun pinnedMask(): Int

    @JvmStatic
    external fun reset()
}
