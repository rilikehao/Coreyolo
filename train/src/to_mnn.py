#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键转换脚本：将 ONNX 模型转换为 MNN 模型并进行量化
使用 MNNConvert 工具进行模型转换，然后使用 quantized.out 进行量化
"""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import List, Optional, Tuple


class ColoredPrinter:
    """带颜色的打印输出"""
    
    @staticmethod
    def red(text: str) -> str:
        return f"\033[0;31m{text}\033[0m"
    
    @staticmethod
    def green(text: str) -> str:
        return f"\033[0;32m{text}\033[0m"
    
    @staticmethod
    def yellow(text: str) -> str:
        return f"\033[1;33m{text}\033[0m"
    
    @staticmethod
    def blue(text: str) -> str:
        return f"\033[0;34m{text}\033[0m"
    
    @staticmethod
    def info(text: str):
        print(f"{ColoredPrinter.blue('[INFO]')} {text}")
    
    @staticmethod
    def success(text: str):
        print(f"{ColoredPrinter.green('[SUCCESS]')} {text}")
    
    @staticmethod
    def warning(text: str):
        print(f"{ColoredPrinter.yellow('[WARNING]')} {text}")
    
    @staticmethod
    def error(text: str):
        print(f"{ColoredPrinter.red('[ERROR]')} {text}")


class MNNConverter:
    """MNN 模型转换器"""
    
    def __init__(self):
        self.script_dir = Path(__file__).parent.resolve()
        
        # 路径配置
        self.mnnconvert_path = self.script_dir / "../../x86_64/root/usr/local/bin/MNNConvert"
        self.quantized_path = self.script_dir / "../../x86_64/root/usr/local/bin/quantized.out"
        self.getmnninfo_path = self.script_dir / "../../x86_64/root/usr/local/bin/GetMNNInfo"
        self.ld_library_path = self.script_dir / "../../x86_64/root/usr/local/lib:../../x86_64/root/usr/lib"
        self.onnx_model_path = self.script_dir / "../best.onnx"
        self.output_dir = self.script_dir / ".."
        self.config_dir = self.script_dir / ".."

        self.env = os.environ.copy()
        self.env["LD_LIBRARY_PATH"] = f"{self.ld_library_path}:{self.env.get('LD_LIBRARY_PATH', '')}"
        
        # 默认参数
        self.framework = "ONNX"
        self.batch_size: Optional[str] = None
        self.fp16 = False
        self.optimize_level = 1
        self.optimize_prefer = 0
        self.keep_input_format = True
        self.do_quantization = False
        self.quant_config_file: Optional[Path] = None
        self.quant_bits = 8
        self.quant_method = "KL"
        self.weight_quant_method = "MAX_ABS"
        self.quant_clamp_value = 127
        self.used_image_num = 100
        self.image_path: Optional[str] = None
    
    def parse_args(self) -> argparse.Namespace:
        """解析命令行参数"""
        parser = argparse.ArgumentParser(
            description="一键转换脚本：将 ONNX 模型转换为 MNN 模型并进行量化",
            formatter_class=argparse.RawDescriptionHelpFormatter,
            epilog="""
示例:
    %(prog)s                          # 基本转换（不量化）
    %(prog)s -b 1                     # 设置批次大小为1
    %(prog)s -f                       # 启用FP16精度
    %(prog)s --quant                  # 转换后进行量化
    %(prog)s --quant --quant-config my_config.json  # 使用自定义配置文件量化
    %(prog)s --quant --quant-method EMA --image-path /path/to/images  # 使用EMA方法和指定图片路径
            """
        )
        
        parser.add_argument(
            "-b", "--batch",
            type=str,
            help="设置批次大小 (默认: 模型原始批次)"
        )
        
        parser.add_argument(
            "-f", "--fp16",
            action="store_true",
            help="启用 FP16 精度保存"
        )
        
        parser.add_argument(
            "-o", "--optimize",
            type=int,
            choices=[0, 1, 2],
            default=1,
            help="优化级别 (0:不优化, 1:标准优化, 2:激进优化)"
        )
        
        parser.add_argument(
            "-p", "--prefer",
            type=int,
            choices=[0, 1, 2],
            default=0,
            help="优化偏好 (0:正常, 1:最小模型, 2:最快速度)"
        )
        
        parser.add_argument(
            "--no-keep-input-format",
            action="store_true",
            help="不保持输入维度格式"
        )
        
        parser.add_argument(
            "--output-dir",
            type=Path,
            default=self.output_dir,
            help=f"输出目录 (默认: {self.output_dir})"
        )
        
        parser.add_argument(
            "--quant",
            action="store_true",
            help="启用量化 (使用 quantized.out)"
        )
        
        parser.add_argument(
            "--quant-config",
            type=Path,
            help="量化配置文件路径"
        )
        
        parser.add_argument(
            "--quant-bits",
            type=int,
            choices=range(2, 9),
            default=8,
            help="量化位数 (默认: 8)"
        )
        
        parser.add_argument(
            "--quant-method",
            choices=["KL", "ADMM", "EMA"],
            default="KL",
            help="特征量化方法: KL, ADMM, EMA (默认: KL)"
        )
        
        parser.add_argument(
            "--weight-method",
            choices=["MAX_ABS", "ADMM"],
            default="MAX_ABS",
            help="权重量化方法: MAX_ABS, ADMM (默认: MAX_ABS)"
        )
        
        parser.add_argument(
            "--clamp-value",
            type=int,
            default=127,
            help="量化范围 (默认: 127)"
        )
        
        parser.add_argument(
            "--image-num",
            type=int,
            default=100,
            help="用于量化的图片数量 (默认: 100)"
        )
        
        parser.add_argument(
            "--image-path",
            type=str,
            help="用于量化的图片目录路径"
        )
        
        args = parser.parse_args()
        
        # 更新实例变量
        self.batch_size = args.batch
        self.fp16 = args.fp16
        self.optimize_level = args.optimize
        self.optimize_prefer = args.prefer
        self.keep_input_format = not args.no_keep_input_format
        self.output_dir = args.output_dir
        self.do_quantization = args.quant
        self.quant_config_file = args.quant_config
        self.quant_bits = args.quant_bits
        self.quant_method = args.quant_method
        self.weight_quant_method = args.weight_method
        self.quant_clamp_value = args.clamp_value
        self.used_image_num = args.image_num
        self.image_path = args.image_path
        
        # 如果指定了量化相关参数，自动启用量化
        if (args.quant_config or args.quant_bits != 8 or 
            args.quant_method != "KL" or args.weight_method != "MAX_ABS" or
            args.clamp_value != 127 or args.image_num != 100 or args.image_path):
            self.do_quantization = True
        
        return args
    
    def check_dependencies(self):
        """检查依赖"""
        ColoredPrinter.info("检查依赖...")
        
        # 检查 MNNConvert
        if not self.mnnconvert_path.exists():
            ColoredPrinter.error(f"MNNConvert 工具不存在: {self.mnnconvert_path}")
            sys.exit(1)
        
        if not os.access(self.mnnconvert_path, os.X_OK):
            ColoredPrinter.error(f"MNNConvert 工具没有执行权限: {self.mnnconvert_path}")
            self.mnnconvert_path.chmod(0o755)
            ColoredPrinter.info("已添加执行权限")
        
        # 检查量化相关工具
        if self.do_quantization:
            if not self.quantized_path.exists():
                ColoredPrinter.error(f"quantized.out 工具不存在: {self.quantized_path}")
                sys.exit(1)
            
            if not os.access(self.quantized_path, os.X_OK):
                ColoredPrinter.error(f"quantized.out 工具没有执行权限: {self.quantized_path}")
                self.quantized_path.chmod(0o755)
                ColoredPrinter.info("已添加执行权限")
            
            if not self.getmnninfo_path.exists():
                ColoredPrinter.error(f"GetMNNInfo 工具不存在: {self.getmnninfo_path}")
                sys.exit(1)
            
            if not os.access(self.getmnninfo_path, os.X_OK):
                ColoredPrinter.error(f"GetMNNInfo 工具没有执行权限: {self.getmnninfo_path}")
                self.getmnninfo_path.chmod(0o755)
                ColoredPrinter.info("已添加执行权限")
        
        # 检查 ONNX 模型
        if not self.onnx_model_path.exists():
            ColoredPrinter.error(f"ONNX 模型文件不存在: {self.onnx_model_path}")
            sys.exit(1)
        
        ColoredPrinter.success("依赖检查完成")
    
    def create_output_dir(self):
        """创建输出目录"""
        self.output_dir.mkdir(parents=True, exist_ok=True)
        ColoredPrinter.info(f"输出目录: {self.output_dir}")
        
        self.config_dir.mkdir(parents=True, exist_ok=True)
        ColoredPrinter.info(f"配置目录: {self.config_dir}")
    
    def get_model_input_dims(self, mnn_file: Path) -> Tuple[int, int]:
        """获取模型输入尺寸"""
        if not mnn_file.exists():
            return 0,0
                
        # 使用 GetMNNInfo 获取模型信息
        result = subprocess.run(
            [str(self.getmnninfo_path), str(mnn_file)],
            capture_output=True,
            text=True,
            check=True,
            env=self.env
        )
        
        mnn_info = result.stdout
        
        print (mnn_info)

        # 解析 GetMNNInfo 的输出，查找输入尺寸信息
        # 通常输出格式类似：input_0: [1, 3, 640, 640]
        import re
        input_match = re.search(r'size: \[([0-9,\s]+)\]', mnn_info)
        
        if not input_match:
            return 1,1
        
        dims_str = input_match.group(1)
        dims = [int(x.strip()) for x in dims_str.split(',')]
        
        if len(dims) < 2:
            return 2,2
        
        # 获取最后两个维度作为高度和宽度
        height = dims[-2]
        width = dims[-1]
        
        return width, height
    
    def create_quant_config(self) -> Path:
        """创建量化配置文件"""
        # 如果用户指定了配置文件，直接使用
        if self.quant_config_file and self.quant_config_file.exists():
            ColoredPrinter.info(f"使用用户指定的量化配置文件: {self.quant_config_file}")
            return self.quant_config_file
        
        config_file = self.config_dir / "quant_config.json"
        ColoredPrinter.info(f"创建默认量化配置文件: {config_file}")
        
        # 获取转换后的MNN模型文件路径
        base_name = self.onnx_model_path.stem
        mnn_file = self.output_dir / f"{base_name}.mnn"
        
        # 使用GetMNNInfo获取模型输入尺寸
        ColoredPrinter.info("使用 GetMNNInfo 获取模型输入尺寸...")
        input_width, input_height = self.get_model_input_dims(mnn_file)
        ColoredPrinter.info(f"获取到模型输入尺寸: {input_width}x{input_height}")
        
        # 如果用户指定了图片路径，使用它；否则使用默认路径
        image_path = self.image_path or str(self.script_dir / "../data/subset")
        
        config_data = {
            "format": "RGB",
            "mean": [0.0, 0.0, 0.0],
            "normal": [0.00784314, 0.00784314, 0.00784314],
            "width": input_width,
            "height": input_height,
            "path": image_path,
            "used_sample_num": self.used_image_num,
            "feature_quantize_method": self.quant_method,
            "weight_quantize_method": self.weight_quant_method,
            "feature_clamp_value": self.quant_clamp_value,
            "weight_clamp_value": self.quant_clamp_value,
            "batch_size": 32,
            "quant_bits": self.quant_bits,
            "skip_quant_op_names": [],
            "input_type": "image",
            "debug": False
        }
        
        with open(config_file, 'w', encoding='utf-8') as f:
            json.dump(config_data, f, indent=4, ensure_ascii=False)
        
        ColoredPrinter.success(f"量化配置文件创建完成: {config_file}")
        return config_file
    
    def build_convert_command(self) -> List[str]:
        """构建 MNNConvert 命令"""
        cmd = [str(self.mnnconvert_path)]
        
        # 基本参数
        cmd.extend(["-f", self.framework])
        cmd.extend(["--modelFile", str(self.onnx_model_path)])
        
        # 输出文件名（原始MNN模型）
        base_name = self.onnx_model_path.stem
        output_file = self.output_dir / f"{base_name}.mnn"
        cmd.extend(["--MNNModel", str(output_file)])
        
        # 可选参数
        if self.batch_size:
            cmd.extend(["--batch", self.batch_size])
        
        if self.keep_input_format:
            cmd.append("--keepInputFormat")
        
        cmd.extend(["--optimizeLevel", str(self.optimize_level)])
        cmd.extend(["--optimizePrefer", str(self.optimize_prefer)])
        
        if self.fp16:
            cmd.append("--fp16")
        
        return cmd
    
    def build_quant_command(self, config_file: Path) -> List[str]:
        """构建量化命令"""
        base_name = self.onnx_model_path.stem
        original_mnn = self.output_dir / f"{base_name}.mnn"
        quantized_mnn = self.output_dir / f"{base_name}_quant.mnn"
        
        cmd = [
            str(self.quantized_path),
            str(original_mnn),
            str(quantized_mnn),
            str(config_file)
        ]
        
        return cmd
    
    def run_conversion(self):
        """执行转换"""
        ColoredPrinter.info("开始模型转换...")
        
        cmd = self.build_convert_command()
        ColoredPrinter.info(f"执行转换命令: {' '.join(cmd)}")
        
        # 显示转换参数
        print("----------------------------------------")
        print("转换参数:")
        print(f"  框架: {self.framework}")
        print(f"  输入模型: {self.onnx_model_path}")
        print(f"  批次大小: {self.batch_size or '默认'}")
        print(f"  FP16精度: {self.fp16}")
        print(f"  优化级别: {self.optimize_level}")
        print(f"  优化偏好: {self.optimize_prefer}")
        print(f"  保持输入格式: {self.keep_input_format}")
        print(f"  是否量化: {self.do_quantization}")
        print("----------------------------------------")
        
        # 执行转换
        try:
            subprocess.run(cmd, check=True, env=self.env)
            ColoredPrinter.success("MNN 模型转换完成!")
            
            # 显示输出文件信息
            base_name = self.onnx_model_path.stem
            output_file = self.output_dir / f"{base_name}.mnn"
            
            if output_file.exists():
                file_size = output_file.stat().st_size
                size_mb = file_size / (1024 * 1024)
                ColoredPrinter.success(f"输出文件: {output_file} (大小: {size_mb:.2f} MB)")
                
        except subprocess.CalledProcessError as e:
            ColoredPrinter.error(f"MNN 模型转换失败! 错误代码: {e.returncode}")
            sys.exit(1)
    
    def run_quantization(self):
        """执行量化"""
        if not self.do_quantization:
            return
        
        ColoredPrinter.info("开始模型量化...")
        
        # 创建量化配置文件
        config_file = self.create_quant_config()
        
        # 构建量化命令
        cmd = self.build_quant_command(config_file)
        ColoredPrinter.info(f"执行量化命令: {' '.join(cmd)}")
        
        # 显示量化参数
        print("----------------------------------------")
        print("量化参数:")
        print(f"  配置文件: {config_file}")
        print(f"  量化位数: {self.quant_bits}")
        print(f"  特征量化方法: {self.quant_method}")
        print(f"  权重量化方法: {self.weight_quant_method}")
        print(f"  量化范围: {self.quant_clamp_value}")
        print(f"  使用图片数量: {self.used_image_num}")
        print(f"  图片路径: {self.image_path or '默认'}")
        print("----------------------------------------")
        
        # 执行量化
        try:
            subprocess.run(cmd, check=True, env=self.env)
            ColoredPrinter.success("模型量化完成!")
            
            # 显示量化后文件信息
            base_name = self.onnx_model_path.stem
            quantized_file = self.output_dir / f"{base_name}_quant.mnn"
            
            if quantized_file.exists():
                file_size = quantized_file.stat().st_size
                size_mb = file_size / (1024 * 1024)
                ColoredPrinter.success(f"量化后文件: {quantized_file} (大小: {size_mb:.2f} MB)")
                
        except subprocess.CalledProcessError as e:
            ColoredPrinter.error(f"模型量化失败! 错误代码: {e.returncode}")
            ColoredPrinter.warning("提示: 请确保 MNN 库路径正确，并且所有必需的共享库都存在")
            sys.exit(1)
    
    def run(self):
        """主函数"""
        ColoredPrinter.info("YOLO11 ONNX 到 MNN 模型转换脚本")
        ColoredPrinter.info("=================================")
        
        self.parse_args()
        self.check_dependencies()
        self.create_output_dir()
        self.run_conversion()
        self.run_quantization()
        
        ColoredPrinter.success("所有操作完成!")


def main():
    """主入口函数"""
    converter = MNNConverter()
    converter.run()


if __name__ == "__main__":
    main()
