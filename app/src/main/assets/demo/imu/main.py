"""IMU 姿态 demo：三轴加速度 / 角速度 / 欧拉角实时曲线。

固定区：状态 + 三张曲线图
折叠区：采样率、开关各传感器、暂停/继续、校准提示
"""

import math
import os
import time

import ansylab

RATES = ["25", "50", "100", "200"]


def main():
    imu = ansylab.imu()
    if not ansylab.imu_available():
        ansylab.label("本机没有可用的运动传感器", region="main")
        return

    err = imu.enable("accel", "gyro", "game", rate_hz=50)
    if err:
        ansylab.label("传感器打开失败：%s" % err, region="main")
        return

    # ---------------- 固定区：三张图 ----------------
    # window：只显示最近 10 秒，滚动更直观；y_min/y_max：锁定量程，曲线不再忽大忽小
    status = ansylab.label("采样中 ...", region="main")
    plot_acc = ansylab.plot("加速度 (m/s²)", 2000, 3, region="main",
                            y_min=-20, y_max=20, window=10.0)
    plot_gyro = ansylab.plot("角速度 (rad/s)", 2000, 3, region="main",
                             y_min=-10, y_max=10, window=10.0)
    plot_euler = ansylab.plot("姿态角 (°)", 2000, 3, region="main",
                              y_min=-180, y_max=180, window=10.0)

    # ---------------- 折叠区 ----------------
    rate_box = ansylab.combo_box("采样率 (Hz)", RATES, 1)
    run_sw = ansylab.toggle("采集开关", True)
    zero_btn = ansylab.button("暂停并清零曲线")
    export_btn = ansylab.button("导出 CSV")
    info = ansylab.label("", region="panel")

    def export_csv():
        """把三条曲线各存一份 CSV 到数据目录 captures/ 下。"""
        outs = []
        for name, p in (("accel", plot_acc), ("gyro", plot_gyro), ("euler", plot_euler)):
            try:
                outs.append(name + " → " + os.path.basename(p.export_csv()))
            except Exception as e:
                outs.append(name + " 失败：" + repr(e))
        set_info("已导出：" + "，".join(outs))

    running = True
    last_status = None
    last_info = None
    count = 0
    t0 = time.time()

    def set_status(text):
        nonlocal last_status
        if text == last_status:
            return
        last_status = text
        status.set(text)

    def set_info(text):
        nonlocal last_info
        if text == last_info:
            return
        last_info = text
        info.set(text)

    # 等第一帧数据（传感器注册是异步的，刚 enable 时读不到值）
    waited = 0.0
    while ansylab.running() and waited < 3.0:
        if imu.sample() is not None:
            break
        ansylab.sleep(0.05)
        waited += 0.05

    prev_rate = rate_box.value

    while ansylab.running():
        if rate_box.value != prev_rate:
            prev_rate = rate_box.value
            imu.disable()
            imu.enable("accel", "gyro", "game", rate_hz=int(prev_rate))

        if export_btn.clicked:
            export_csv()

        if zero_btn.clicked:
            running = False
            run_sw.value = False
            set_status("已暂停（曲线保留最后一屏）")

        if bool(run_sw.value) != running:
            running = bool(run_sw.value)
            set_status("采样中 ..." if running else "已暂停")
            if running:
                t0 = time.time()
                count = 0

        if not running:
            ansylab.sleep(0.1)
            continue

        snap = imu.sample()
        if snap is None:
            ansylab.sleep(0.02)
            continue
        (ax, ay, az), (gx, gy, gz), (yaw, pitch, roll) = snap

        plot_acc.push(ax, ay, az)
        plot_gyro.push(gx, gy, gz)
        plot_euler.push(yaw, pitch, roll)

        count += 1
        now = time.time()
        if now - t0 >= 0.5:
            hz = count / (now - t0)
            count = 0
            t0 = now
            set_status("a=(%.2f, %.2f, %.2f) g=(%.2f, %.2f, %.2f) | 采样 %s Hz / 刷新 %.0f Hz" % (
                ax, ay, az, gx, gy, gz, prev_rate, hz))
            set_info("姿态：方位 %.1f°  俯仰 %.1f°  横滚 %.1f°" % (yaw, pitch, roll))

        # 按采样率决定轮询间隔（比采样周期再快一倍）
        ansylab.sleep(max(0.002, 0.5 / max(1.0, float(prev_rate))))


if __name__ == "__main__":
    main()
