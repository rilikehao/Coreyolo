#!/bin/bash
export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:$(echo $HOME/.local/share/uv/python/cpython-3.9.*/lib)
export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:$(pwd)/../deps/ascend-toolkit/6.0.1/x86_64-linux/devlib
source ../env-huawei/bin/activate
source ../deps/ascend-toolkit/set_env.sh
atc --model ../best_deploy_model.onnx --output ../best --soc_version=Ascend$1 --framework=5
