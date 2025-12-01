#!/usr/bin/env python3
# -*- coding: UTF-8 -*-
"""
Copyright (C) 2019. Huawei Technologies Co., Ltd. All rights reserved.

This program is free software; you can redistribute it and/or modify
it under the terms of the Apache License Version 2.0.You may not use
this file except in compliance with the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
Apache License for more details at
http://www.apache.org/licenses/LICENSE-2.0

data process script.

"""
import os
import numpy as np
import cv2 # pylint: disable=E0401
import subprocess

from pathlib import Path
import onnxruntime as ort
import os

def format_shape(shape_list):
    """
    将形状列表转换为逗号分隔的字符串，并处理 None 值。
    """
    # 将 None 转换为 '?' 或 'None'，以便更好地表示动态维度
    # 这里我们使用 '?' 来表示未知或动态维度
    formatted_dims = [str(dim) if dim is not None and dim != -1 else '?' for dim in shape_list]
    
    # 将格式化后的维度用逗号连接起来
    return ",".join(formatted_dims)

def get_onnx_input_info(onnx_model_path):
    """
    使用 ONNX Runtime 获取 ONNX 模型的输入名称和形状，并修复了 'providers' 错误。
    """
    if not os.path.exists(onnx_model_path):
        return [f"错误：找不到文件 {onnx_model_path}"]

    try:
        # 1. 明确指定要使用的执行提供者
        #    根据您的错误提示，我们显式地设置 'CPUExecutionProvider'。
        #    如果您有 GPU，可以添加 'CUDAExecutionProvider' 或 'AzureExecutionProvider' 等。
        providers_list = ['CPUExecutionProvider'] 
        
        # 2. 创建 InferenceSession，现在加入了 providers 参数
        session = ort.InferenceSession(onnx_model_path, providers=providers_list) 
        
    except Exception as e:
        # 如果还有其他错误，可以打印出来
        return [f"加载 ONNX Runtime Session 时发生错误: {e}"]

    # --- 您的原始逻辑保持不变 ---
    formatted_outputs = []
    
    for input_meta in session.get_inputs():
        name = input_meta.name
        shape = input_meta.shape
        shape_str = ",".join([str(dim) if dim is not None and dim != -1 else '?' for dim in shape])
        formatted_line = f"{name}:{shape_str}"
        formatted_outputs.append(formatted_line)
        
    return formatted_outputs[0]

script_dir = Path(__file__).parent.resolve()

TRAIN_PATH = script_dir / ".."
IMAGE_PATH = script_dir / "../data"
BIN_PATH = script_dir / "../data/huawei"

def get_images_from_txt(label_file):
    """Read all images' name"""
    image_names = []
    label_file = os.path.realpath(label_file)
    with open(label_file, 'r') as fid:
        lines = fid.readlines()
        for line in lines:
            image_names.append(line.split(' ')[0].strip())
    return image_names

def prepare_image_input(images, height=320, width=576):
    input_array = np.zeros((len(images), 3, height, width), np.float32)

    imgs = np.zeros((len(images), 3, height, width), np.float32)
    for index, im_file in enumerate(images):
        im_file = im_file.strip()
        im_data = cv2.imread(im_file)
        im_data = cv2.resize(
            im_data, (width, height), interpolation=cv2.INTER_CUBIC)
        cv2.cvtColor(im_data, cv2.COLOR_BGR2RGB)

        imgs[index] = im_data.transpose(2, 0, 1).astype(np.float32)

    input_array = imgs
    input_array /= 255.0
    return input_array

def prepare():
    """process image and save it to bin"""
    image_names = get_images_from_txt(os.path.join(IMAGE_PATH, 'subset.txt'))
    for image_name in image_names:
        names = [os.path.join(IMAGE_PATH, image_name)]
        input_array = prepare_image_input(names)
        if not os.path.exists(BIN_PATH):
            os.mkdir(BIN_PATH)
        output = image_name.split('/')[-1] + ".bin"
        input_array.tofile(os.path.join(BIN_PATH, output))
    return len(image_names)

if __name__ == '__main__':
    image_count = prepare()
    info = get_onnx_input_info(str(TRAIN_PATH / "best.onnx"))
    command_list = [
        str(TRAIN_PATH / "env-huawei-6/bin/amct_onnx"),
        "calibration",
        "--model", str(TRAIN_PATH / "best.onnx"),
        "--save_path", str(TRAIN_PATH / "best"),
        "--input_shape", str(info),
        "--data_dir", str(TRAIN_PATH / "data/huawei"),
        "--data_types", "float32",
        "--batch_num", str(image_count),
    ]
    subprocess.run(command_list, check=True)
    command_list = [
        "bash",
        "to_ascend_310.sh",
    ]
    subprocess.run(command_list, check=True)
