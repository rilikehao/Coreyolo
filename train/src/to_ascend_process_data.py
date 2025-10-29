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

PATH = os.path.realpath('./')
IMAGE_PATH = os.path.join(PATH, './data')
BIN_PATH = os.path.join(PATH, './data/calibration')
BIN_FILE = os.path.join(BIN_PATH, 'calibration.bin')
MEAN = [0.0, 0.0, 0.0]
STD = [0.00784314, 0.00784314, 0.00784314]
CALIBRATION_SIZE = 16

def get_labels_from_txt(label_file):
    """Read all images' name and label from label_file"""
    image_names = []
    labels = []
    label_file = os.path.realpath(label_file)
    with open(label_file, 'r') as fid:
        lines = fid.readlines()
        for line in lines:
            image_names.append(line.split(' ')[0])
            labels.append('')
    return image_names, labels


def prepare_image_input(images, height=480, width=640):
    input_array = np.zeros((len(images), 3, height, width), np.float32)
    mean = MEAN
    std = STD

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


def process_data():
    """process data"""
    # prepare cur batch data
    image_names, labels = get_labels_from_txt(
        os.path.join(IMAGE_PATH, 'subset.txt'))
    if len(labels) < CALIBRATION_SIZE:
        raise RuntimeError(
            'num of image in {} is less than total_num{}'
            .format(IMAGE_PATH, CALIBRATION_SIZE))
    labels = labels[0:CALIBRATION_SIZE]
    image_names = image_names[0:CALIBRATION_SIZE]
    image_names = [
        os.path.join(IMAGE_PATH, image_name) for image_name in image_names
    ]
    input_array = prepare_image_input(image_names)
    return input_array


def main():
    """process image and save it to bin"""
    input_array = process_data()
    if not os.path.exists(BIN_PATH):
        os.mkdir(BIN_PATH)
    input_array.tofile(BIN_FILE)


if __name__ == '__main__':
    main()
