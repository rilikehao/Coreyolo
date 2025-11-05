#!/usr/bin/env python3
"""
YOLO11 RKNN 一键转换脚本
"""

import sys
import os
import argparse
from pathlib import Path
from rknn.api import RKNN

# 颜色定义
class Colors:
    RED = '\033[0;31m'
    GREEN = '\033[0;32m'
    YELLOW = '\033[1;33m'
    BLUE = '\033[0;34m'
    NC = '\033[0m'  # No Color

# 默认配置
DEFAULT_ONNX_PATH = '../best.onnx'
DATASET_PATH = '../data/subset.txt'
DEFAULT_RKNN_PATH = '../best.rk3576'
DEFAULT_QUANT = True
PLATFORM = "rk3576"

def print_info(message):
    """打印带颜色的信息"""
    print(f"{Colors.BLUE}[INFO]{Colors.NC} {message}")

def print_success(message):
    """打印带颜色的成功信息"""
    print(f"{Colors.GREEN}[SUCCESS]{Colors.NC} {message}")

def print_error(message):
    """打印带颜色的错误信息"""
    print(f"{Colors.RED}[ERROR]{Colors.NC} {message}")

def print_warning(message):
    """打印带颜色的警告信息"""
    print(f"{Colors.YELLOW}[WARNING]{Colors.NC} {message}")

def check_files():
    """检查必要文件是否存在"""
    if not os.path.exists(DEFAULT_ONNX_PATH):
        print_error("YOLO11n ONNX 模型不存在")
        print_info("请先运行: cd assets && bash download_model.sh")
        return False
    
    if not os.path.exists(DATASET_PATH):
        print_error("量化数据集文件不存在")
        return False
    
    return True

def show_usage():
    """显示使用说明"""
    print("======================================")
    print("YOLO11 RKNN 一键转换脚本")
    print("======================================")
    print()
    print("使用方法：")
    print("  python3 to_rknn_new.py [dtype] [output_path]")
    print()
    print("参数：")
    print("  dtype        - 数据类型 (i8 或 fp，默认 i8)")
    print("  output_path  - 输出文件路径 (默认 install/yolo11.rknn)")
    print()
    print("示例：")
    print("  python3 to_rknn_new.py                    # 使用默认 i8 量化")
    print("  python3 to_rknn_new.py i8                 # i8 量化")
    print("  python3 to_rknn_new.py fp                 # fp 不量化")
    print("  python3 to_rknn_new.py i8 my_model.rknn   # 自定义输出路径")
    print()

def parse_arguments():
    """解析命令行参数"""
    parser = argparse.ArgumentParser(description='YOLO11 RKNN 一键转换脚本', add_help=False)
    
    # 处理 --help 和 -h 参数
    parser.add_argument('--help', '-h', action='store_true', help='显示帮助信息')
    
    # 位置参数
    parser.add_argument('dtype', nargs='?', default='i8', 
                       choices=['i8', 'fp'], help='数据类型 (i8 或 fp，默认 i8)')
    parser.add_argument('output_path', nargs='?', default=DEFAULT_RKNN_PATH, 
                       help='输出文件路径 (默认 install/yolo11.rknn)')
    
    args = parser.parse_args()
    
    if args.help:
        show_usage()
        sys.exit(0)
    
    return args.dtype, args.output_path

def convert_model(dtype, output_path):
    """转换模型"""
    # 创建 install 目录
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    
    print_info("开始转换模型...")
    print_info(f"数据类型: {dtype}")
    print_info(f"输出路径: {output_path}")
    
    # 确定是否量化
    do_quant = (dtype == 'i8')
    
    # 创建 RKNN 对象
    rknn = RKNN(verbose=False)
    
    try:
        # 预处理配置
        print('--> Config model')
        rknn.config(mean_values=[[0, 0, 0]], std_values=[[255, 255, 255]], target_platform=PLATFORM)
        print('done')
        
        # 加载模型
        print('--> Loading model')
        ret = rknn.load_onnx(model=DEFAULT_ONNX_PATH)
        if ret != 0:
            print_error('Load model failed!')
            return ret
        print('done')
        
        # 构建模型
        print('--> Building model')
        ret = rknn.build(do_quantization=do_quant, dataset=DATASET_PATH)
        if ret != 0:
            print_error('Build model failed!')
            return ret
        print('done')
        
        # 导出 rknn 模型
        print('--> Export rknn model')
        ret = rknn.export_rknn(output_path)
        if ret != 0:
            print_error('Export rknn model failed!')
            return ret
        print('done')
        
        # 释放资源
        rknn.release()
        
        print_success("模型转换完成！")
        print_info(f"输出文件: {output_path}")
        return 0
        
    except Exception as e:
        print_error(f"模型转换过程中发生错误: {str(e)}")
        if 'rknn' in locals():
            rknn.release()
        return 1

def main():
    """主函数"""
    # 如果参数为 --help 或 -h，显示使用说明
    if len(sys.argv) > 1 and (sys.argv[1] == '--help' or sys.argv[1] == '-h'):
        show_usage()
        sys.exit(0)
    
    print("======================================")
    print("YOLO11 RKNN 一键转换脚本")
    print("======================================")
    print()
    
    # 检查文件
    if not check_files():
        sys.exit(1)
    
    # 解析参数
    dtype, output_path = parse_arguments()
    
    # 转换模型
    ret = convert_model(dtype, output_path)
    
    print()
    if ret == 0:
        print_success("转换完成！")
    else:
        print_error("转换失败！")
    
    sys.exit(ret)

if __name__ == '__main__':
    main()
