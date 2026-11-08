#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#define LOG_TAG "ansylab-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 用纯 C 导出给 Python 的 ctypes 调用；Android 的 .so 默认可见性可能是 hidden，
// 所以每个导出都显式标注 default。
#define ANSYLAB_API __attribute__((visibility("default")))

namespace {

// 三个槽位，每个槽位「借用」一帧 CameraX 的 ImageProxy：
// Python 读的就是相机自己的 buffer，读完（release）再 close，全程不拷贝。
// 比「拷进 native 帧池」又省掉一次 3 MB/frame 的 memcpy。
constexpr int kSlots = 3;

struct BorrowedSlot {
    jobject proxy = nullptr;   // ImageProxy 的 GlobalRef，release 时 close
    uint8_t *data = nullptr;   // CameraX direct buffer 的 CPU 指针（已加 position 偏移）
    int width = 0;
    int height = 0;
    int rowStride = 0;
};

BorrowedSlot gSlots[kSlots];
std::atomic<int> gPinned[kSlots];
std::atomic<long long> gSeq{0};
std::atomic<int> gLatest{-1};
std::atomic<int> gNext{0};
std::atomic<long long> gDropped{0};
std::atomic<int> gWidth{0};
std::atomic<int> gHeight{0};
std::atomic<long long> gBorrowed{0};   // 累计借出的帧数
std::atomic<int> gRotationDegrees{0};  // CameraX 给出的旋转角（0/90/180/270）
std::atomic<int> gRowStride{0};        // 相机每行的字节数（可能 > width*4）

jmethodID gProxyCloseMethod = nullptr;
jmethodID gBufferPositionMethod = nullptr;

// ---------------------------------------------------------------------------
// 模型输入缓冲（float32 NCHW）
//
// 以前：Python 用 cv2 做好 blob -> tobytes() 4.9 MB -> Chaquopy 再拷成 Java
//       byte[] -> ORT 再拷进 tensor。一次推理额外搬了 ~10 MB。
// 现在：native 里分配一块 float 缓冲，Python 把 numpy 视图直接建在上面，
//       cv2 写完后 Kotlin 从同一块内存建 tensor，只剩 ORT 自己那一次拷贝。
// ---------------------------------------------------------------------------
std::mutex gInputLock;
std::vector<float> gInputBuf;

// 帧槽 / 显示缓冲的并发保护。
//
// 会并发访问它们的有三方：
//   1. CameraX 分析线程：pushFrame → pushBorrowed
//   2. Python 线程：acquireFrame / releaseFrame / beginDraw / endDraw
//   3. 主线程：退出程序页时 NativeCamera.reset() 一次性清理
//
// 之前这三方没有互斥，reset 关掉槽位的同时 pushBorrowed 可能又把 ImageProxy
// 塞回去，导致那个 proxy 永远不会被 close —— CameraX 不再出帧，表现为
// 「多次开关后偶发卡死」。显示缓冲同理：释放时 Python 正在画，会留下悬空指针。
std::recursive_mutex gSlotLock;
std::recursive_mutex gHwLock;

std::mutex gDisplayLock;
std::vector<uint8_t> gDisplay;
std::atomic<int> gDisplayW{0};
std::atomic<int> gDisplayH{0};
std::atomic<long long> gDisplaySeq{0};

// ---------------------------------------------------------------------------
// 硬件显示缓冲（AHardwareBuffer 双缓冲）
//
// 软缓冲那条路要走 Bitmap.copyPixelsFromBuffer，等于每帧多一次 3 MB memcpy。
// 改成 AHardwareBuffer 之后，Python 直接写进 GPU 采样的那块内存，
// Bitmap 用 wrapHardwareBuffer 包一层，渲染时不再拷贝。
//
// 双缓冲是为了避免「Python 正在写、GPU 正在读」的撕裂。
// ---------------------------------------------------------------------------
constexpr int kHwBuffers = 2;

AHardwareBuffer *gHwBuf[kHwBuffers] = {nullptr, nullptr};
jobject gHwRef[kHwBuffers] = {nullptr, nullptr};
int gHwW = 0;
int gHwH = 0;
int gHwStrideBytes = 0;
bool gHwDirect = false;        // stride 是否正好等于 w*4（可以直接让 cv2 写）
bool gHwDrawing = false;
int gHwBack = 0;               // Python 正在画的缓冲
int gHwFront = 0;              // UI 正在显示的缓冲
uint8_t *gHwLocked = nullptr;
std::vector<uint8_t> gStaging; // stride 不匹配时的中转缓冲

void releaseHwLocked() {
    if (gHwDrawing && gHwLocked != nullptr) {
        AHardwareBuffer_unlock(gHwBuf[gHwBack], nullptr);
    }
    gHwLocked = nullptr;
    gHwDrawing = false;
}

void releaseHwBuffers(JNIEnv *env) {
    std::lock_guard<std::recursive_mutex> lock(gHwLock);
    releaseHwLocked();
    for (int i = 0; i < kHwBuffers; ++i) {
        if (gHwRef[i] != nullptr && env != nullptr) {
            env->DeleteGlobalRef(gHwRef[i]);
        }
        gHwRef[i] = nullptr;
        gHwBuf[i] = nullptr;
    }
    gHwW = 0;
    gHwH = 0;
    gHwStrideBytes = 0;
    gHwDirect = false;
    // 索引也要复位：否则下次绑定后 front/back 可能指到刚被清空的那一格。
    gHwFront = 0;
    gHwBack = 0;
}

int pickSlot() {
    const int start = gNext.load(std::memory_order_relaxed);
    for (int i = 0; i < kSlots; ++i) {
        const int idx = (start + i) % kSlots;
        if (gPinned[idx].load(std::memory_order_acquire) == 0) {
            return idx;
        }
    }
    return -1;
}

jmethodID proxyCloseMethod(JNIEnv *env, jobject proxy) {
    if (gProxyCloseMethod == nullptr && env != nullptr && proxy != nullptr) {
        jclass cls = env->GetObjectClass(proxy);
        if (cls != nullptr) {
            gProxyCloseMethod = env->GetMethodID(cls, "close", "()V");
            env->DeleteLocalRef(cls);
        }
    }
    return gProxyCloseMethod;
}

/** 归还一帧给相机：close 之后 CameraX 才会交出下一帧。 */
void closeProxy(JNIEnv *env, jobject proxy) {
    if (proxy == nullptr || env == nullptr) {
        return;
    }
    jmethodID method = proxyCloseMethod(env, proxy);
    if (method != nullptr) {
        env->CallVoidMethod(proxy, method);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
}

/** 关闭槽位并释放 GlobalRef；之后该槽位可以被下一帧复用。调用方需持有 gSlotLock。 */
void closeSlot(JNIEnv *env, int idx) {
    BorrowedSlot &slot = gSlots[idx];
    if (slot.proxy != nullptr) {
        if (env != nullptr) {
            closeProxy(env, slot.proxy);
            env->DeleteGlobalRef(slot.proxy);
        }
        slot.proxy = nullptr;
    }
    slot.data = nullptr;
    slot.width = 0;
    slot.height = 0;
    slot.rowStride = 0;
}

/**
 * 借出一帧：只记下 ImageProxy 与指针，不拷贝像素。
 * 调用方负责在 Python 读完时 closeSlot（由 releaseFrame 触发）。
 */
void pushBorrowed(JNIEnv *env, jobject proxy, uint8_t *data, int rowStride, int w, int h,
                  int rotationDegrees) {
    std::lock_guard<std::recursive_mutex> lock(gSlotLock);

    const int idx = pickSlot();
    if (idx < 0) {
        // 三个槽都被 Python 占着：直接还给相机，丢这一帧。
        const long long dropped = gDropped.fetch_add(1, std::memory_order_relaxed) + 1;
        if (dropped % 30 == 1) {
            LOGE("帧池已满（累计丢弃 %lld），Python 可能没及时 release", dropped);
        }
        closeProxy(env, proxy);
        return;
    }

    BorrowedSlot &slot = gSlots[idx];
    if (slot.proxy != nullptr) {
        closeSlot(env, idx);  // 理论上不会发生
    }
    slot.proxy = env->NewGlobalRef(proxy);
    slot.data = data;
    slot.width = w;
    slot.height = h;
    slot.rowStride = rowStride;

    gWidth.store(w);
    gHeight.store(h);
    gRotationDegrees.store(rotationDegrees);
    gRowStride.store(rowStride);
    gLatest.store(idx, std::memory_order_release);
    gSeq.fetch_add(1, std::memory_order_release);
    gBorrowed.fetch_add(1, std::memory_order_relaxed);
    gNext.store((idx + 1) % kSlots, std::memory_order_relaxed);
}

uint8_t *ensureDisplay(int w, int h) {
    std::lock_guard<std::mutex> lock(gDisplayLock);
    const size_t need = static_cast<size_t>(w) * static_cast<size_t>(h) * 4;
    if (gDisplay.size() < need || gDisplayW.load() != w || gDisplayH.load() != h) {
        gDisplay.assign(need, 0);
        gDisplayW.store(w);
        gDisplayH.store(h);
        LOGI("显示缓冲重建 %dx%d", w, h);
    }
    return gDisplay.data();
}

// --- 硬件显示缓冲 -----------------------------------------------------------

void hwProbeStride() {
    AHardwareBuffer_Planes planes{};
    if (AHardwareBuffer_lockPlanes(gHwBuf[0], AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                                   -1, nullptr, &planes) == 0) {
        gHwStrideBytes = planes.planes[0].rowStride;
        AHardwareBuffer_unlock(gHwBuf[0], nullptr);
    }
    gHwDirect = (gHwStrideBytes == gHwW * 4);
    if (!gHwDirect) {
        gStaging.assign(static_cast<size_t>(gHwW) * gHwH * 4, 0);
    }
    LOGI("硬件显示 %dx%d stride=%d %s", gHwW, gHwH, gHwStrideBytes,
         gHwDirect ? "直写" : "经中转");
}

}  // namespace

// ===========================================================================
// 给 Python（ctypes）用的纯 C 接口
// ===========================================================================
extern "C" {

ANSYLAB_API int ansylab_acquire() {
    std::lock_guard<std::recursive_mutex> lock(gSlotLock);
    const int idx = gLatest.load(std::memory_order_acquire);
    if (idx < 0) {
        return -1;
    }
    int expected = 0;
    if (!gPinned[idx].compare_exchange_strong(expected, 1)) {
        return -1;  // 已经有一个读者占着这一帧
    }
    return idx;
}

ANSYLAB_API void ansylab_release(int slot) {
    std::lock_guard<std::recursive_mutex> lock(gSlotLock);
    if (slot >= 0 && slot < kSlots) {
        gPinned[slot].store(0, std::memory_order_release);
    }
}

/** 调试用：哪些槽位还被占着（bit i = slot i）。 */
ANSYLAB_API int ansylab_pinned_mask() {
    int mask = 0;
    for (int i = 0; i < kSlots; ++i) {
        if (gPinned[i].load(std::memory_order_acquire) != 0) {
            mask |= (1 << i);
        }
    }
    return mask;
}

ANSYLAB_API uint8_t *ansylab_slot_ptr(int slot) {
    std::lock_guard<std::recursive_mutex> lock(gSlotLock);
    if (slot < 0 || slot >= kSlots || gSlots[slot].data == nullptr) {
        return nullptr;
    }
    return gSlots[slot].data;
}

ANSYLAB_API int ansylab_width() { return gWidth.load(); }

ANSYLAB_API int ansylab_height() { return gHeight.load(); }

ANSYLAB_API long long ansylab_seq() { return gSeq.load(std::memory_order_acquire); }

ANSYLAB_API long long ansylab_dropped() { return gDropped.load(std::memory_order_relaxed); }

ANSYLAB_API uint8_t *ansylab_display_ptr() {
    std::lock_guard<std::mutex> lock(gDisplayLock);
    return gDisplay.empty() ? nullptr : gDisplay.data();
}

ANSYLAB_API int ansylab_display_width() { return gDisplayW.load(); }

ANSYLAB_API int ansylab_display_height() { return gDisplayH.load(); }

ANSYLAB_API uint8_t *ansylab_display_resize(int w, int h) { return ensureDisplay(w, h); }

ANSYLAB_API void ansylab_present() { gDisplaySeq.fetch_add(1, std::memory_order_release); }

ANSYLAB_API long long ansylab_display_seq() {
    return gDisplaySeq.load(std::memory_order_acquire);
}

ANSYLAB_API void ansylab_reset() {
    for (auto &slot : gSlots) {
        // 不能在这里 close ImageProxy：没有 JNIEnv。JNI 的 reset 会先调
        // closeSlot 把每一帧还回去，再走这里清计数。
        slot.proxy = nullptr;
        slot.data = nullptr;
        slot.width = 0;
        slot.height = 0;
        slot.rowStride = 0;
    }
    for (auto &pin : gPinned) {
        pin.store(0);
    }
    gLatest.store(-1);
    gNext.store(0);
    gSeq.store(0);
    gDropped.store(0);
    gBorrowed.store(0);
    gWidth.store(0);
    gHeight.store(0);
}

}  // extern "C"

// ===========================================================================
// JNI：推帧、取帧指针、显示缓冲
//
// 注意：这里不依赖 .so 解压到磁盘。AGP 默认 extractNativeLibs=false，库直接
// 从 APK 映射，`dlopen("<nativeLibraryDir>/libansylab.so")` 会失败，所以 Python
// 侧改为通过 JNI 拿「指针地址」，再用 ctypes 在该地址上建 numpy 视图。
// 下面的纯 C 导出只在开启 jniLibs.useLegacyPackaging=true 时才可 dlopen。
// ===========================================================================
extern "C" JNIEXPORT void JNICALL
Java_com_ansylab_vision_NativeCamera_pushFrame(JNIEnv *env, jclass /* clazz */,
                                               jobject image, jobject buffer,
                                               jint rowStride, jint width, jint height,
                                               jint rotationDegrees) {
    auto *base = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    if (base == nullptr) {
        LOGE("pushFrame: 传入的不是 direct ByteBuffer");
        closeProxy(env, image);
        return;
    }

    // ByteBuffer 的 position 可能不是 0，指针要加上偏移。
    if (gBufferPositionMethod == nullptr) {
        jclass bufferClass = env->FindClass("java/nio/Buffer");
        if (bufferClass != nullptr) {
            gBufferPositionMethod = env->GetMethodID(bufferClass, "position", "()I");
            env->DeleteLocalRef(bufferClass);
        }
    }
    jint position = 0;
    if (gBufferPositionMethod != nullptr) {
        position = env->CallIntMethod(buffer, gBufferPositionMethod);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            position = 0;
        }
    }

    pushBorrowed(env, image, base + position, rowStride, width, height, rotationDegrees);
}

/** 相机帧需要的旋转角：CameraX 已经转正时为 0。 */
extern "C" JNIEXPORT jint JNICALL
Java_com_ansylab_vision_NativeCamera_rotationDegrees(JNIEnv *, jclass) {
    return gRotationDegrees.load();
}

/** 相机帧的行字节数。真机常见 rowStride > width*4（行尾有 padding），
 *  Python 侧必须用它建 stride 视图，否则画面会斜向撕裂。 */
extern "C" JNIEXPORT jint JNICALL
Java_com_ansylab_vision_NativeCamera_frameRowStride(JNIEnv *, jclass) {
    return gRowStride.load();
}

// --- 模型输入缓冲 -----------------------------------------------------------

/** 分配（或复用）float32 输入缓冲，返回首地址；0 表示失败。 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_ansylab_vision_NativeCamera_allocInput(JNIEnv *, jclass, jint floatCount) {
    if (floatCount <= 0) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(gInputLock);
    if (gInputBuf.size() < static_cast<size_t>(floatCount)) {
        gInputBuf.assign(static_cast<size_t>(floatCount), 0.0f);
        LOGI("输入缓冲分配 %.2f MB", floatCount * 4.0 / 1048576.0);
    }
    return reinterpret_cast<jlong>(gInputBuf.data());
}

/** 输入缓冲的 direct ByteBuffer 视图（交给 ORT 建 tensor）。 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_ansylab_vision_NativeCamera_inputBuffer(JNIEnv *env, jclass, jint floatCount) {
    std::lock_guard<std::mutex> lock(gInputLock);
    if (gInputBuf.empty() || floatCount <= 0 ||
        static_cast<size_t>(floatCount) > gInputBuf.size()) {
        return nullptr;
    }
    return env->NewDirectByteBuffer(gInputBuf.data(),
                                    static_cast<jlong>(floatCount) * 4);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ansylab_vision_NativeCamera_acquireFrame(JNIEnv *, jclass) {
    return ansylab_acquire();
}

extern "C" JNIEXPORT void JNICALL
Java_com_ansylab_vision_NativeCamera_releaseFrame(JNIEnv *env, jclass, jint slot) {
    if (slot < 0 || slot >= kSlots) {
        return;
    }
    std::lock_guard<std::recursive_mutex> lock(gSlotLock);
    // 先解引用计数把帧还给相机，再放开槽位让下一帧可以进来。
    closeSlot(env, slot);
    gPinned[slot].store(0, std::memory_order_release);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ansylab_vision_NativeCamera_framePtr(JNIEnv *, jclass, jint slot) {
    return reinterpret_cast<jlong>(ansylab_slot_ptr(slot));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ansylab_vision_NativeCamera_frameWidth(JNIEnv *, jclass) {
    return gWidth.load();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ansylab_vision_NativeCamera_frameHeight(JNIEnv *, jclass) {
    return gHeight.load();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ansylab_vision_NativeCamera_displayResize(JNIEnv *, jclass, jint w, jint h) {
    return reinterpret_cast<jlong>(ansylab_display_resize(w, h));
}

extern "C" JNIEXPORT void JNICALL
Java_com_ansylab_vision_NativeCamera_markPresented(JNIEnv *, jclass) {
    ansylab_present();
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_ansylab_vision_NativeCamera_displayBuffer(JNIEnv *env, jclass /* clazz */) {
    const int w = gDisplayW.load();
    const int h = gDisplayH.load();
    if (w <= 0 || h <= 0 || gDisplay.empty()) {
        return nullptr;
    }
    return env->NewDirectByteBuffer(gDisplay.data(),
                                    static_cast<jlong>(w) * h * 4);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ansylab_vision_NativeCamera_displayWidth(JNIEnv *, jclass) {
    return gDisplayW.load();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ansylab_vision_NativeCamera_displayHeight(JNIEnv *, jclass) {
    return gDisplayH.load();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ansylab_vision_NativeCamera_displaySeq(JNIEnv *, jclass) {
    return static_cast<jlong>(gDisplaySeq.load(std::memory_order_acquire));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ansylab_vision_NativeCamera_droppedFrames(JNIEnv *, jclass) {
    return static_cast<jlong>(gDropped.load(std::memory_order_relaxed));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ansylab_vision_NativeCamera_frameSeq(JNIEnv *, jclass) {
    return static_cast<jlong>(gSeq.load(std::memory_order_acquire));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ansylab_vision_NativeCamera_pinnedMask(JNIEnv *, jclass) {
    return ansylab_pinned_mask();
}

extern "C" JNIEXPORT void JNICALL
Java_com_ansylab_vision_NativeCamera_reset(JNIEnv *env, jclass) {
    // 停相机时必须把借出去的帧全部还回去，否则 CameraX 会一直等 close，
    // 下次启动相机拿不到新帧。
    // 加锁：分析线程可能正在 pushFrame，Python 线程可能在 beginDraw/endDraw。
    {
        std::lock_guard<std::recursive_mutex> lock(gSlotLock);
        for (int i = 0; i < kSlots; ++i) {
            closeSlot(env, i);
        }
        ansylab_reset();
    }
    releaseHwBuffers(env);
}

// --- 硬件显示：Kotlin 侧创建 AHardwareBuffer，这里只负责锁定/解锁 ---------

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ansylab_vision_NativeCamera_setDisplayBuffers(
        JNIEnv *env, jclass, jobject buffer0, jobject buffer1, jint width, jint height) {
    std::lock_guard<std::recursive_mutex> lock(gHwLock);
    releaseHwBuffers(env);
    if (buffer0 == nullptr || buffer1 == nullptr || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }

    jobject refs[kHwBuffers] = {buffer0, buffer1};
    for (int i = 0; i < kHwBuffers; ++i) {
        gHwRef[i] = env->NewGlobalRef(refs[i]);
        gHwBuf[i] = AHardwareBuffer_fromHardwareBuffer(env, refs[i]);
        if (gHwBuf[i] == nullptr) {
            LOGE("setDisplayBuffers: AHardwareBuffer_fromHardwareBuffer 返回空");
            releaseHwBuffers(env);
            return JNI_FALSE;
        }
    }

    gHwW = width;
    gHwH = height;
    gHwBack = 0;
    gHwFront = 1;
    // 让 displayWidth/Height 在硬件路径下也能返回正确值（UI 靠它判断尺寸）。
    gDisplayW.store(width);
    gDisplayH.store(height);
    hwProbeStride();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ansylab_vision_NativeCamera_beginDraw(JNIEnv *, jclass) {
    std::lock_guard<std::recursive_mutex> lock(gHwLock);
    if (gHwBuf[gHwBack] == nullptr) {
        return 0;
    }
    if (gHwDrawing) {
        // 同一帧里多次 imshow：复用已经锁定的那块，避免重复 lock。
        return reinterpret_cast<jlong>(gHwDirect ? gHwLocked : gStaging.data());
    }

    AHardwareBuffer_Planes planes{};
    if (AHardwareBuffer_lockPlanes(gHwBuf[gHwBack], AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                                   -1, nullptr, &planes) != 0) {
        LOGE("beginDraw: lockPlanes 失败");
        return 0;
    }

    gHwLocked = static_cast<uint8_t *>(planes.planes[0].data);
    gHwStrideBytes = planes.planes[0].rowStride;
    gHwDrawing = true;

    // stride 和宽度对不上时，先写中转缓冲，endDraw 再按行搬过去。
    if (gHwStrideBytes == gHwW * 4) {
        gHwDirect = true;
        return reinterpret_cast<jlong>(gHwLocked);
    }
    gHwDirect = false;
    if (gStaging.size() < static_cast<size_t>(gHwW) * gHwH * 4) {
        gStaging.assign(static_cast<size_t>(gHwW) * gHwH * 4, 0);
    }
    return reinterpret_cast<jlong>(gStaging.data());
}

extern "C" JNIEXPORT void JNICALL
Java_com_ansylab_vision_NativeCamera_endDraw(JNIEnv *, jclass) {
    std::lock_guard<std::recursive_mutex> lock(gHwLock);
    if (!gHwDrawing || gHwBuf[gHwBack] == nullptr) {
        return;
    }

    if (!gHwDirect) {
        const size_t rowBytes = static_cast<size_t>(gHwW) * 4;
        for (int y = 0; y < gHwH; ++y) {
            std::memcpy(gHwLocked + static_cast<size_t>(y) * gHwStrideBytes,
                        gStaging.data() + static_cast<size_t>(y) * rowBytes,
                        rowBytes);
        }
    }

    AHardwareBuffer_unlock(gHwBuf[gHwBack], nullptr);
    gHwLocked = nullptr;
    gHwDrawing = false;

    // 交换前后台：刚画完的变成前台，另一个留给下一帧。
    gHwFront = gHwBack;
    gHwBack = (gHwBack + 1) % kHwBuffers;
    gDisplaySeq.fetch_add(1, std::memory_order_release);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ansylab_vision_NativeCamera_frontIndex(JNIEnv *, jclass) {
    std::lock_guard<std::recursive_mutex> lock(gHwLock);
    return gHwFront;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ansylab_vision_NativeCamera_hasHardwareDisplay(JNIEnv *, jclass) {
    std::lock_guard<std::recursive_mutex> lock(gHwLock);
    return (gHwBuf[0] != nullptr && gHwBuf[1] != nullptr) ? JNI_TRUE : JNI_FALSE;
}

// 保留一个版本串，方便确认 native 库确实加载成功。
extern "C" JNIEXPORT jstring JNICALL
Java_com_ansylab_vision_MainActivity_nativeVersion(JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF("ansylab-native/0.2 (zero-copy)");
}
