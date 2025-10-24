# CoreYolo

基于 YOLO11 的跨平台目标检测推理框架，支持 RK3588 和 x64 架构。

## 特性

- 🚀 高性能 YOLO11 目标检测推理
- 🔄 跨平台支持：RK3588 (ARM64) 和 x64 (x86_64)
- 🎯 多种推理引擎：MNN、RKNN、ONNX
- 📡 RTSP 流输出支持，实时推流处理结果
- 📦 一键式构建和部署
- 🛠️ 集成训练环境，支持模型导出和量化
- 📱 支持 AppImage 打包部署
- ⚡ 优化的输出结构，360p 分辨率下达到 30 FPS

## YOLO11 优化特性

### 输出结构优化
以 640x640 尺寸为例，优化后的模型输出三个张量：
- `[1, 64, 80, 80]` - 边界框坐标
- `[1, 80, 80, 80]` - 80 个类别的置信度分数
- `[1, 1, 80, 80]` - 所有类别的置信度总和

### 性能实测数据
使用 YOLO11 官方 S 大小模型（参数量 9.4M）的实测性能：
- **AMD Ryzen 5 7640HS w/ Radeon 760M Graphics**：360p 分辨率下达到 30 FPS
- **RK3588**：360p 分辨率下达到 30 FPS

测试条件：使用 INT8 量化，标准推理配置，实际应用场景。

## 项目结构

```
CoreYolo/
├── src/main/kotlin/          # 主构建脚本 (Kotlin)
├── infer/src/nativeMain/     # 推理应用 (Kotlin Native)
├── native/                   # C++ 原生代码
├── assets/                   # 模型和测试资源
├── train/                    # 训练环境和工具
├── rk3588/                   # RK3588 交叉编译环境
├── x64/                      # x64 构建环境
└── lua/                      # Lua 脚本示例
```

## 快速开始

### 环境要求

- CMake 3.20+
- Meson (用于 RK3588)
- 交叉编译工具链 (aarch64-linux-gnu)

### 构建命令

```bash
# 1. 构建所有依赖和组件
./gradlew run

# 2. 编译推理应用
./gradlew :infer:install

# 3. 生成 AppImage 部署包
./gradlew run --args='image'
```

## 平台支持

### RK3588 平台
- **RGA (Rockchip Graphics Accelerator)** - 图像处理加速
- **RKNN** - 瑞芯微 NPU 推理
- **专用优化库支持**
- **依赖库**：librknnrt.so、librga.so

### x64 平台
- **FFmpeg** - 视频解码和处理
- **MNN** - 移动端神经网络推理框架
- **Vulkan 加速支持**
- **依赖库**：libMNN.so、FFmpeg

### 模型格式支持
| 格式 | 用途 | 目标平台 |
|------|------|----------|
| ONNX | 通用格式 | 跨平台 |
| MNN | 移动端推理 | x64 |
| RKNN | 瑞芯微 NPU | RK3588 |

> **注意**：RK3588 平台必须使用 RKNN 模型，x64 平台必须使用 MNN 模型。

### 使用示例

```bash
# 图像推理
x64/YoloInfer.AppImage --source-type image --model assets/yolo11s.mnn --description assets/yolo11s.txt --source assets/bus.jpg

# 流媒体推理
x64/YoloInfer.AppImage --source-type video --model assets/yolo11s.mnn --description assets/yolo11s.txt --draw-script lua/example.lua --source rtsp://lax.kw92.cyou:8554/2025/2025.mp4 

# 摄像头推理
x64/YoloInfer.AppImage --source-type video --model assets/yolo11s.mnn --description assets/yolo11s.txt --draw-script lua/example.lua --source /dev/video0

# 图像推理 (在 rk3588 运行)
rk3588/YoloInfer.AppImage --source-type image --model assets/yolo11s.rknn --description assets/yolo11s.txt --source assets/bus.jpg

# RTSP 流输出
x64/YoloInfer.AppImage --source-type video --model assets/yolo11s.mnn --description assets/yolo11s.txt --source rtsp://127.0.0.1:8554/input/stream.mp4 --target rtsp://127.0.0.1:8554/output/stream.mp4

# 其他类似
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

#### 5. 准备校准数据

在 `data/subset` 目录中放置一些图片用于量化校准，并更新 `data/subset.txt` 文件，列出用于模型量化的校准图片路径。

#### 6. 导出模型

运行导出命令：

```bash
./gradlew run --args="train-export"
```

该命令会自动导出以下模型格式：
- `best.onnx`
- `best.mnn`
- `best.rknn`

```bash
./gradlew run --args="train-clean"
```
