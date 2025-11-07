extern "C" {
#include "native.h"
}

#include <acl/acl.h>

#include "image.h"
#include "inference.h"

struct Session {
    uint32_t model_id_ = 0;
    aclmdlDesc* model_desc_ = nullptr;
    int h_, w_;
};

struct Infer {
    std::vector<std::string> names_;
    std::vector<Session> sessions_;
    int device_id_ = 0;
    bool initialized_ = false;
};

struct InferTask {
    Image* image_;
    std::vector<Detection> detections_;
    int infer_;
    float scale_;

    std::vector<aclmdlDataset*> output_datasets_;
};

namespace {

bool QueryAcl(aclError ret, const char* operation) {
    if (ret != ACL_SUCCESS) {
        qDebug(
            "ACL operation %s failed, errorCode is %d", operation, ret);
        return false;
    }
    return true;
}

bool QueryModelInfo(Session* s) {
    if (!s->model_desc_) return false;

    // 获取模型输入维度
    aclmdlIODims input_dims;
    aclError ret = aclmdlGetInputDims(s->model_desc_, 0, &input_dims);
    if (!QueryAcl(ret, "aclmdlGetInputDims")) return false;

    if (input_dims.dimCount != 4) {
        qDebug("Invalid input dims count: %zu", input_dims.dimCount);
        return false;
    }

    // NHWC 格式: dims[0]=N, dims[1]=H, dims[2]=W, dims[3]=C
    s->h_ = static_cast<int>(input_dims.dims[1]);
    s->w_ = static_cast<int>(input_dims.dims[2]);
    qDebug("model input height=%d, width=%d", s->h_, s->w_);
    return true;
}

}  // namespace

Infer* CreateInfer(InferConfig* config) {
    auto infer = new Infer;
    InitNames(infer->names_, config->path_description_);

    // 初始化 ACL
    infer->device_id_ = 0;  // 默认设备ID
    aclError ret = aclInit(nullptr);
    if (!QueryAcl(ret, "aclInit")) {
        delete infer;
        return nullptr;
    }

    // 创建设备上下文
    aclrtContext context;
    ret = aclrtCreateContext(&context, infer->device_id_);
    if (!QueryAcl(ret, "aclrtCreateContext")) {
        aclFinalize();
        delete infer;
        return nullptr;
    }

    // 设置运行模式
    aclrtRunMode runMode;
    ret = aclrtGetRunMode(&runMode);
    if (!QueryAcl(ret, "aclrtGetRunMode")) {
        aclrtDestroyContext(context);
        aclFinalize();
        delete infer;
        return nullptr;
    }

    // 创建会话
    infer->sessions_.resize(config->threads_);

    for (auto& session : infer->sessions_) {
        // 加载模型
        ret =
            aclmdlLoadFromFile(config->path_model_, &session.model_id_);
        if (!QueryAcl(ret, "aclmdlLoadFromFile")) {
            // 清理已加载的模型
            for (auto& s : infer->sessions_) {
                if (s.model_id_ != 0) {
                    aclmdlUnload(s.model_id_);
                }
            }
            aclrtDestroyContext(context);
            aclFinalize();
            delete infer;
            return nullptr;
        }

        // 创建模型描述
        session.model_desc_ = aclmdlCreateDesc();
        if (!session.model_desc_) {
            qDebug("Failed to create model description");
            aclmdlUnload(session.model_id_);
            for (auto& s : infer->sessions_) {
                if (s.model_id_ != 0) {
                    aclmdlUnload(s.model_id_);
                }
            }
            aclrtDestroyContext(context);
            aclFinalize();
            delete infer;
            return nullptr;
        }

        // 获取模型描述
        ret = aclmdlGetDesc(session.model_desc_, session.model_id_);
        if (!QueryAcl(ret, "aclmdlGetDesc")) {
            aclmdlDestroyDesc(session.model_desc_);
            aclmdlUnload(session.model_id_);
            for (auto& s : infer->sessions_) {
                if (s.model_id_ != 0) {
                    aclmdlUnload(s.model_id_);
                }
            }
            aclrtDestroyContext(context);
            aclFinalize();
            delete infer;
            return nullptr;
        }

        // 获取模型信息
        QueryModelInfo(&session);
    }

    infer->initialized_ = true;
    qDebug("ACL context initialized successfully");
    return infer;
}

void DestroyInfer(Infer* infer) {
    if (!infer) return;

    for (auto& session : infer->sessions_) {
        if (session.model_desc_) {
            aclmdlDestroyDesc(session.model_desc_);
            session.model_desc_ = nullptr;
        }
        if (session.model_id_ != 0) {
            aclmdlUnload(session.model_id_);
            session.model_id_ = 0;
        }
    }

    // 释放ACL资源
    if (infer->initialized_) {
        aclFinalize();
        infer->initialized_ = false;
    }

    delete infer;
}

struct InferTask* CreateInferTask() { return new InferTask; }

void DestroyInferTask(struct InferTask* task) {
    if (task) {
        // 清理输出数据集
        for (auto dataset : task->output_datasets_) {
            if (dataset) {
                // 释放数据集中的每个缓冲区
                size_t num_buffers =
                    aclmdlGetDatasetNumBuffers(dataset);
                for (size_t i = 0; i < num_buffers; ++i) {
                    aclDataBuffer* buffer =
                        aclmdlGetDatasetBuffer(dataset, i);
                    if (buffer) {
                        void* data = aclGetDataBufferAddr(buffer);
                        if (data) {
                            aclrtFree(data);
                        }
                        aclDestroyDataBuffer(buffer);
                    }
                }
                aclmdlDestroyDataset(dataset);
            }
        }
        task->output_datasets_.clear();
        delete task;
    }
}

void Detect0(Infer* infer, InferTask* task, int no) {
    if (!infer || !task ||
        no >= static_cast<int>(infer->sessions_.size())) {
        throw std::runtime_error("Invalid parameters for Detect0");
    }

    auto& session = infer->sessions_[no];
    int h = session.h_;
    int w = session.w_;

    // 缩放图像到模型输入尺寸
    QImage scaled =
        ScalePadToRGB(task->image_->data_, w, h, task->scale_);
    const uchar* data = scaled.constBits();

    // 创建输入数据集
    aclmdlDataset* input_dataset = aclmdlCreateDataset();
    if (!input_dataset) {
        throw std::runtime_error("Failed to create input dataset");
    }

    // 计算输入数据大小
    size_t input_size = static_cast<size_t>(h) * w * 3;
    void* input_buffer = nullptr;
    aclError ret = aclrtMalloc(
        &input_buffer, input_size, ACL_MEM_MALLOC_NORMAL_ONLY);
    if (ret != ACL_SUCCESS) {
        qDebug("Failed to malloc input buffer, errorCode is %d", ret);
        aclmdlDestroyDataset(input_dataset);
        throw std::runtime_error("Failed to allocate input buffer");
    }

    // 复制数据到设备内存
    memcpy(input_buffer, data, input_size);

    // 创建输入数据缓冲
    aclDataBuffer* input_buffer_desc =
        aclCreateDataBuffer(input_buffer, input_size);
    if (!input_buffer_desc) {
        qDebug("Failed to create input data buffer");
        aclrtFree(input_buffer);
        aclmdlDestroyDataset(input_dataset);
        throw std::runtime_error("Failed to create input data buffer");
    }

    // 添加到数据集
    ret = aclmdlAddDatasetBuffer(input_dataset, input_buffer_desc);
    if (ret != ACL_SUCCESS) {
        qDebug(
            "Failed to add input dataset buffer, errorCode is %d", ret);
        aclDestroyDataBuffer(input_buffer_desc);
        aclrtFree(input_buffer);
        aclmdlDestroyDataset(input_dataset);
        throw std::runtime_error("Failed to add input dataset buffer");
    }

    // 创建输出数据集
    aclmdlDataset* output_dataset = aclmdlCreateDataset();
    if (!output_dataset) {
        qDebug("Failed to create output dataset");
        aclDestroyDataBuffer(input_buffer_desc);
        aclrtFree(input_buffer);
        aclmdlDestroyDataset(input_dataset);
        throw std::runtime_error("Failed to create output dataset");
    }

    // 获取模型输出数量
    size_t num_outputs = aclmdlGetNumOutputs(session.model_desc_);
    qDebug("Model has %zu outputs", num_outputs);

    // 为每个输出创建缓冲区
    for (size_t i = 0; i < num_outputs; ++i) {
        size_t output_size =
            aclmdlGetOutputSizeByIndex(session.model_desc_, i);
        void* output_buffer = nullptr;

        ret = aclrtMalloc(
            &output_buffer, output_size, ACL_MEM_MALLOC_NORMAL_ONLY);
        if (ret != ACL_SUCCESS) {
            qDebug(
                "Failed to malloc output buffer %zu, errorCode is %d",
                i, ret);
            // 清理已分配的内存
            size_t num_created = i;
            for (size_t j = 0; j < num_created; ++j) {
                aclDataBuffer* buffer =
                    aclmdlGetDatasetBuffer(output_dataset, j);
                if (buffer) {
                    void* data = aclGetDataBufferAddr(buffer);
                    if (data) aclrtFree(data);
                    aclDestroyDataBuffer(buffer);
                }
            }
            aclmdlDestroyDataset(output_dataset);
            aclDestroyDataBuffer(input_buffer_desc);
            aclrtFree(input_buffer);
            aclmdlDestroyDataset(input_dataset);
            throw std::runtime_error(
                "Failed to allocate output buffer");
        }

        aclDataBuffer* output_buffer_desc =
            aclCreateDataBuffer(output_buffer, output_size);
        if (!output_buffer_desc) {
            qDebug("Failed to create output data buffer %zu", i);
            aclrtFree(output_buffer);
            // 清理已分配的内存
            size_t num_created = i;
            for (size_t j = 0; j < num_created; ++j) {
                aclDataBuffer* buffer =
                    aclmdlGetDatasetBuffer(output_dataset, j);
                if (buffer) {
                    void* data = aclGetDataBufferAddr(buffer);
                    if (data) aclrtFree(data);
                    aclDestroyDataBuffer(buffer);
                }
            }
            aclmdlDestroyDataset(output_dataset);
            aclDestroyDataBuffer(input_buffer_desc);
            aclrtFree(input_buffer);
            aclmdlDestroyDataset(input_dataset);
            throw std::runtime_error(
                "Failed to create output data buffer");
        }

        ret =
            aclmdlAddDatasetBuffer(output_dataset, output_buffer_desc);
        if (ret != ACL_SUCCESS) {
            qDebug(
                "Failed to add output dataset buffer %zu, errorCode is "
                "%d",
                i, ret);
            aclDestroyDataBuffer(output_buffer_desc);
            aclrtFree(output_buffer);
            // 清理已分配的内存
            size_t num_created = i;
            for (size_t j = 0; j < num_created; ++j) {
                aclDataBuffer* buffer =
                    aclmdlGetDatasetBuffer(output_dataset, j);
                if (buffer) {
                    void* data = aclGetDataBufferAddr(buffer);
                    if (data) aclrtFree(data);
                    aclDestroyDataBuffer(buffer);
                }
            }
            aclmdlDestroyDataset(output_dataset);
            aclDestroyDataBuffer(input_buffer_desc);
            aclrtFree(input_buffer);
            aclmdlDestroyDataset(input_dataset);
            throw std::runtime_error(
                "Failed to add output dataset buffer");
        }
    }

    // 执行推理
    ret =
        aclmdlExecute(session.model_id_, input_dataset, output_dataset);
    if (!QueryAcl(ret, "aclmdlExecute")) {
        // 清理所有资源
        size_t num_buffers = aclmdlGetDatasetNumBuffers(output_dataset);
        for (size_t i = 0; i < num_buffers; ++i) {
            aclDataBuffer* buffer =
                aclmdlGetDatasetBuffer(output_dataset, i);
            if (buffer) {
                void* data = aclGetDataBufferAddr(buffer);
                if (data) aclrtFree(data);
                aclDestroyDataBuffer(buffer);
            }
        }
        aclmdlDestroyDataset(output_dataset);
        aclDestroyDataBuffer(input_buffer_desc);
        aclrtFree(input_buffer);
        aclmdlDestroyDataset(input_dataset);
        throw std::runtime_error("Failed to execute model");
    }

    // 清理输入
    aclDestroyDataBuffer(input_buffer_desc);
    aclrtFree(input_buffer);
    aclmdlDestroyDataset(input_dataset);

    // 保存输出数据集供后续处理使用
    task->output_datasets_.push_back(output_dataset);

    qDebug("ACL inference completed successfully");
}

void Detect1(Infer* infer, InferTask* task) {
    if (!infer || !task || task->output_datasets_.empty()) {
        return;
    }

    PostProcessData data;
    data.names_ = &infer->names_;
    data.detections_ = &task->detections_;
    data.scale_ = task->scale_;

    // YOLO11 处理 9 个输出张量（3个尺度的box, score, score_sum）
    // 从 Ascend 模型获取输出信息
    auto& session = infer->sessions_[0];
    size_t num_outputs = aclmdlGetNumOutputs(session.model_desc_);

    for (size_t b = 0; b < num_outputs && b + 2 < 9; b += 3) {
        if (b >= task->output_datasets_.size() ||
            !task->output_datasets_[b]) {
            qDebug("Warning: Missing output dataset at index %zu", b);
            continue;
        }

        // 获取输出数据
        aclmdlDataset* output_dataset = task->output_datasets_[b];
        aclDataBuffer* box_buffer =
            aclmdlGetDatasetBuffer(output_dataset, 0);
        if (!box_buffer) {
            qDebug("Failed to get box buffer for output %zu", b);
            continue;
        }

        void* box_ptr = aclGetDataBufferAddr(box_buffer);
        size_t box_size = aclGetDataBufferSize(box_buffer);

        // 获取 score 输出
        float* score_ptr = nullptr;
        if (b + 1 < task->output_datasets_.size() &&
            task->output_datasets_[b + 1]) {
            aclDataBuffer* score_buffer = aclmdlGetDatasetBuffer(
                task->output_datasets_[b + 1], 0);
            if (score_buffer) {
                score_ptr = static_cast<float*>(
                    aclGetDataBufferAddr(score_buffer));
            }
        }

        // 获取 score_sum 输出
        float* score_sum_ptr = nullptr;
        if (b + 2 < task->output_datasets_.size() &&
            task->output_datasets_[b + 2]) {
            aclDataBuffer* score_sum_buffer = aclmdlGetDatasetBuffer(
                task->output_datasets_[b + 2], 0);
            if (score_sum_buffer) {
                score_sum_ptr = static_cast<float*>(
                    aclGetDataBufferAddr(score_sum_buffer));
            }
        }

        // 检查数据有效性
        data.box_ = static_cast<float*>(box_ptr);
        data.score_ = score_ptr;
        data.score_sum_ = score_sum_ptr;

        // 获取输出张量维度
        aclmdlIODims output_dims;
        aclError ret =
            aclmdlGetOutputDims(session.model_desc_, b, &output_dims);
        if (QueryAcl(ret, "aclmdlGetOutputDims")) {
            qDebug("输出维度: %dx%dx%dx%d",  //
                   output_dims.dims[0], output_dims.dims[1],
                   output_dims.dims[2], output_dims.dims[3]);
            data.h_grid_ =
                static_cast<int>(output_dims.dims[2]);  // H 维度
            data.w_grid_ =
                static_cast<int>(output_dims.dims[3]);  // W 维度
        } else {
            throw std::runtime_error("Failed to aclmdlGetOutputDims");
        }

        data.stride_ = session.h_ / data.h_grid_;

        // 执行 YOLO11 后处理
        PostProcess(data);
    }

    // 清理输出数据集
    for (auto dataset : task->output_datasets_) {
        if (dataset) {
            size_t num_buffers = aclmdlGetDatasetNumBuffers(dataset);
            for (size_t i = 0; i < num_buffers; ++i) {
                aclDataBuffer* buffer =
                    aclmdlGetDatasetBuffer(dataset, i);
                if (buffer) {
                    void* data_ptr = aclGetDataBufferAddr(buffer);
                    if (data_ptr) {
                        aclrtFree(data_ptr);
                    }
                    aclDestroyDataBuffer(buffer);
                }
            }
            aclmdlDestroyDataset(dataset);
        }
    }
    task->output_datasets_.clear();

    // 非极大值抑制
    NonMaximumSuppression(task->detections_);
}

struct Image* GetImage(struct InferTask* task) { return task->image_; }

void SetImage(struct InferTask* task, struct Image* image) {
    task->image_ = image;
}

int SizeDetections(InferTask* task) {
    return static_cast<int>(task->detections_.size());
}

Detection* PtrDetections(InferTask* task) {
    return task->detections_.data();
}
