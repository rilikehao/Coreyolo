# 构建和执行流程

1. 如果更改了 native/ 文件，./gradlew run --args "native"，否则跳过。
2. 如果更改了 infer/ 文件，./gradlew :infer:install，否则跳过。
3. ./gradlew run --args "image"。
4. x64/YoloInfer.AppImage --source-type video --model assets/yolo11s.mnn --description assets/yolo11s.txt --source rtsp://127.0.0.1:8554/2025/2025.mp4 --draw-script lua/example.lua --target rtsp://127.0.0.1:8554/2025/2025-00.mp4
