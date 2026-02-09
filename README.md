# CoreYolo

基于 YOLO11 的跨平台目标检测推理框架。

## 特性

- 🚀 高性能 YOLO11 目标检测推理
- 🔄 支持多种推理引擎
- 📡 RTSP 流输出支持，实时推流处理结果
- 📦 一键式构建和部署
- 🛠️ 集成训练环境，支持模型导出和量化
- 📱 支持 AppImage 打包部署

## YOLO11 优化特性

### 输出结构优化

以 640x640 尺寸为例，优化后的模型输出三个张量：
- `[1, 64, 80, 80]` - 边界框坐标
- `[1, 80, 80, 80]` - 80 个类别的置信度分数
- `[1, 1, 80, 80]` - 所有类别的置信度总和

### 性能实测数据

- INT8 量化

#### RK3588

- 使用 YOLO11 官方 S 大小模型
- 576 x 320 分辨率
- 同时处理 6 路视频 + 1 路回放
- 编解码 30 FPS + 图像识别 10 FPS

#### RK3576

- 使用 YOLO11 官方 L 大小模型
- 576 x 320 分辨率
- 同时处理 3 路视频 + 1 路回放
- 编解码 30 FPS + 图像识别 10 FPS

#### Ascend 310P

- 使用 YOLO11 官方 L 大小模型
- 576 x 320 分辨率
- 同时处理 15 路视频 + 1 路回放
- 编解码 30 FPS + 图像识别 10 FPS

#### Ascend 310

- 使用 YOLO11 官方 L 大小模型
- 576 x 320 分辨率
- 同时处理 1 路视频 + 1 路回放 (使用 H264 存储)
- 编解码 30 FPS + 图像识别 30 FPS

## 项目结构

```
CoreYolo/
├── src/main/kotlin/          # 主构建脚本 (Kotlin)
├── infer/src/nativeMain/     # 推理应用 (Kotlin Native)
├── native/                   # C++ 原生代码
├── assets/                   # 模型和测试资源
├── train/                    # 训练环境和工具
└── lua/                      # Lua 脚本示例
```

## 快速开始

### 环境要求

- CMake 3.20+
- 交叉编译工具链 (aarch64-linux-gnu)

### 构建命令

```bash
# 1. 安装系统基本工具包
sudo pacman -S cmake
sudo pacman -S aarch64-linux-gnu-gcc

# 2. 首次构建需要配置 arch 文件
sudo vim /usr/local/bin/arch

# 文件里填写下面的内容。
#!/bin/bash
exec uname -m

# 3. 添加配置文件权限
sudo chmod +x /usr/local/bin/arch

# 4. 构建所有依赖和组件
./gradlew run

# 5. 编译推理应用
./gradlew image
```

## 视频流媒体服务配置

本部分介绍如何部署 ZLMediaKit 流媒体服务器，并通过 FFmpeg 循环推送本地视频流，最后在 `CoreYolo` 项目中进行调用。

### ZLMediaKit 部署与配置

*   **安装服务**
    *   从 [ZLMediaKit GitHub](https://github.com/ZLMediaKit/ZLMediaKit) 下载二进制文件，依次：Issues => 各平台二进制包下载 => 找Linux对应的下载地址。
    *   解压文件后，将Linux下的Release文件名改为 `ZLMediaKit` ，输入以下指令迁移至 `/opt` 目录：
        ```bash
        sudo mv ZLMediaKit /opt/
        ```

*   **修改端口配置**
    *   在`ZLMediaKit`文件中，修改 `/opt/ZLMediaKit/config.ini`，调整以下协议端口以避免冲突：
        *   **[http]** 模块：`port` 修改为 `8080`，`sslport` 修改为 `8443`。
        *   **[rtsp]** 模块：`port` 修改为 `8554`。

---

### 服务配置与推流管理

本部分介绍如何通过 Systemd 管理 ZLMediaKit 及推流任务，以便在需要时快速启动服务。

*   **ZLMediaKit 服务定义**
    *   创建服务文件：`sudo nano /etc/systemd/system/ZLMediaKit.service`
    *   此配置文件定义了流媒体服务器的运行环境。
        ```ini
        [Unit]
        Description=ZLMediaKit Media Server
        After=network.target

        [Service]
        Type=simple
        User=root
        WorkingDirectory=/opt/ZLMediaKit
        ExecStart=/opt/ZLMediaKit/MediaServer
        Restart=always

        [Install]
        WantedBy=multi-user.target
        ```

*   **FFmpeg 推流服务定义**
    *   创建服务文件：`sudo nano /etc/systemd/system/ffmpeg-stream.service`
    *   **注意**：在 `ExecStart` 中，必须根据实际情况修改 `-i` 参数后的**视频文件绝对路径**；`-f rtsp` 参数为推流的目标地址，后续接收视频流时用这个地址。
        ```ini
        [Unit]
        Description=FFmpeg RTSP Streaming Service
        After=network.target

        [Service]
        User=root
        # -i 后面请替换为你本地视频的实际绝对路径
        ExecStart=/usr/bin/ffmpeg -re -stream_loop -1 -i /home/guozhengz/work/CoreYolo/test.mp4 -c copy -bsf:v h264_mp4toannexb -f rtsp rtsp://localhost:8554/2025/test.mp4 -rtsp_transport tcp

        [Install]
        WantedBy=multi-user.target
        ```

---

### 服务操作指令

配置完成后，可以根据需要手动启动或停止服务，无需设置开机自启。

*   **启动视频流服务**
    *   首次配置或修改路径后，需先重新加载配置，然后依次启动服务器和推流脚本：
        ```bash
        sudo systemctl daemon-reload
        sudo systemctl start ZLMediaKit.service
        sudo systemctl start ffmpeg-stream.service
        ```

*   **停止视频流服务**
    *   不需要使用视频流时，可以手动关闭服务以释放系统资源：
        ```bash
        sudo systemctl stop ffmpeg-stream.service
        sudo systemctl stop ZLMediaKit.service
        ```

*   **状态查看**
    *   若推流异常，可通过以下命令查看状态：
        ```bash
        sudo systemctl status ffmpeg-stream.service
        ```

### **项目集成 (CoreYolo)**
*   **配置视频源地址**： 修改 `configs/config-software.toml` 配置文件，将 `stream/source` 项更新为 `FFmpeg` 推送的流地址（`-f rtsp`参数：`rtsp://127.0.0.1:8554/2025/test.mp4`）。

*   **验证**：运行下面使用示例中对应平台设备流媒体推理方式的指令，访问项目的推理网页`view.html`，此时视频流应该已正确加载并显示实时推理结果。

## 平台支持

- **FFmpeg** - 视频解码和处理
- **Qt** - 图像的 GPU 缩放和绘制
- **RGA (Rockchip Graphics Accelerator)** - 图像处理加速
- **RKNN** - 瑞芯微 NPU 推理
- **依赖库**：librknnrt.so、librga.so
- **MNN** - 移动端神经网络推理框架（Vulkan 加速支持）
- **依赖库**：libMNN.so

### 模型格式支持

| 格式 | 用途       | 目标平台 |
|------|------------|----------|
| MNN  | 桌面端推理 | x86_64   |
| RKNN | 瑞芯微 NPU | aarch64  |

### 使用示例

```bash
# 图像推理
x86_64/YoloInfer.AppImage software configs/config-software-image.toml

# 摄像头推理
x86_64/YoloInfer.AppImage software configs/config-software-camera.toml

# 流媒体推理
x86_64/YoloInfer.AppImage software configs/config-software.toml

# 流媒体推理 (在 rk3588 运行)
aarch64/YoloInfer.AppImage rockchip configs/config-rk3588.toml

# 流媒体推理 (在 rk3576 运行)
aarch64/YoloInfer.AppImage rockchip configs/config-rk3576.toml

# 流媒体推理 (在 ascend310 运行)
aarch64/YoloInfer.AppImage ascend configs/config-ascend310.toml

# 流媒体推理 (在 ascend310P 运行)
aarch64/YoloInfer.AppImage ascend configs/config-ascend310P3.toml
```

## YOLO 模型导出工具

将 YOLO 训练好的模型导出为可部署格式。

#### 1. 安装 uv

请确保已安装 uv 包管理器。

#### 2. 设置环境

运行环境设置：

```bash
./gradlew run --args="train"
```

#### 3. 替换模型

将你的训练好的模型文件 `best.pt` 替换项目根目录下的同名文件。

#### 4. 配置图像尺寸

编辑 `ultralytics/ultralytics/cfg/default.yaml` 文件，将 `imgsz` 参数修改为你最终要识别的图片尺寸（必须是 32 的倍数）。
编辑 `src/to_ascend.py` 文件更新图片大小。

#### 5. 准备校准数据

在 `data/subset` 目录中放置一些图片用于量化校准，并更新 `data/subset.txt` 文件，列出用于模型量化的校准图片路径。

#### 6. 导出模型

运行导出命令：

```bash
./gradlew run --args="train-export"
```

该命令会自动导出以下模型格式：
- `best.onnx`
- `best.x86_64`
- `best.rk3588`
- `best.rk3576`
- `best.ascend310`
- `best.ascend310P3`

```bash
./gradlew run --args="train-clean"
```

## TODO

- 支持 AMD，英伟达，寒武纪。
