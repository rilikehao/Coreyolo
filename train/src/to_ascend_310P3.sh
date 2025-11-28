#!/bin/bash
export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:$(echo $HOME/.local/share/uv/python/cpython-3.9.*/lib)
export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:$(pwd)/../deps/8.0.0/ascend-toolkit/8.0.0/x86_64-linux/devlib
source ../env-huawei-8/bin/activate
source ../deps/8.0.0/ascend-toolkit/set_env.sh
atc --model ../best_deploy_model.onnx --output ../best --soc_version=Ascend310P3 --framework=5
