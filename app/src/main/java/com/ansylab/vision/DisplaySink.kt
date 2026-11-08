package com.ansylab.vision

import android.graphics.Bitmap

/**
 * 显示回调：MainActivity 把 Python 画好的帧转交给当前可见的程序页。
 *
 * 相机/显示引擎仍住在 Activity 里，页面只负责「把收到的 Bitmap 贴到 ImageView」，
 * 这样切页时只要换一个 sink，不用重建 native 缓冲。
 */
interface DisplaySink {
    /** 显示缓冲建好了（尺寸变化时也会调），用来按画面比例调整 ImageView。 */
    fun onDisplayReady(width: Int, height: Int)

    /** 新的一帧（硬件路径下同一个 Bitmap 会反复复用）。 */
    fun onFrame(bitmap: Bitmap)

    /** 一行日志，显示在画面下方。 */
    fun onLog(line: String)
}
