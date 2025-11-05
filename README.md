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

**RK3588/RK3576** 的实测性能：
- 使用 YOLO11 官方 S 大小模型（参数量 9.4M）
- 640 x 480 分辨率
- 同时处理 RK3588: 7 路视频, RK3576: 4 路视频
- 编解码 30 FPS
- 图像识别 10 FPS
- 测试条件：使用 INT8 量化

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
# 1. 构建所有依赖和组件
./gradlew run

# 2. 编译推理应用
./gradlew image
```

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
x86_64/YoloInfer.AppImage configs/config-x86_64-sw-image.toml

# 摄像头推理
x86_64/YoloInfer.AppImage configs/config-x86_64-sw-camera.toml

# 流媒体推理
x86_64/YoloInfer.AppImage configs/config-x86_64-sw.toml

# 流媒体推理 (在 rk3588 运行)
aarch64/YoloInfer.AppImage configs/config-rk3588.toml

# 流媒体推理 (在限制功率的 rk3588 运行)
aarch64/YoloInfer.AppImage configs/config-rk3588-degraded.toml

# 流媒体推理 (在 rk3576 运行)
aarch64/YoloInfer.AppImage configs/config-rk3576.toml
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
- `best.x86_64`
- `best.rk3588`
- `best.rk3576`

```bash
./gradlew run --args="train-clean"
```

## TODO

1. [已完成] 整理成 x86_64 和 aarch64 两种编译配置。
2. [已完成] 输入端直连，输出端输出绘制和不绘制的两个流。
3. [已完成] 支持 Lua 截图。
4. 支持 AMD，英伟达，华为，寒武纪。
