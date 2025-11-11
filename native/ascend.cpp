extern "C" {
#include "native.h"
}

#include <acl/acl.h>

#include "image.h"
#include "inference.h"

struct Session {
    uint32_t model_id_ = 0;
    aclmdlDesc* model_desc_ = nullptr;
    aclrtContext context_ = nullptr;
    int h_ = 0;
    int w_ = 0;
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
    std::vector<aclrtContext> output_contexts_;
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

    // NCHW 格式: dims[0]=N, dims[1]=C, dims[2]=H, dims[3]=W
    s->h_ = static_cast<int>(input_dims.dims[2]);
    s->w_ = static_cast<int>(input_dims.dims[3]);
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

    infer->sessions_.resize(config->threads_);

    auto CleanupSessions = [&]() {
        for (auto& s : infer->sessions_) {
            if (s.model_desc_) {
                aclmdlDestroyDesc(s.model_desc_);
                s.model_desc_ = nullptr;
            }
            if (s.model_id_ != 0) {
                aclmdlUnload(s.model_id_);
                s.model_id_ = 0;
            }
            if (s.context_) {
                aclrtDestroyContext(s.context_);
                s.context_ = nullptr;
            }
        }
    };

    bool run_mode_checked = false;

    for (auto& session : infer->sessions_) {
        ret = aclrtCreateContext(&session.context_, infer->device_id_);
        if (!QueryAcl(ret, "aclrtCreateContext")) {
            CleanupSessions();
            aclFinalize();
            delete infer;
            return nullptr;
        }

        ret = aclrtSetCurrentContext(session.context_);
        if (!QueryAcl(ret, "aclrtSetCurrentContext")) {
            CleanupSessions();
            aclFinalize();
            delete infer;
            return nullptr;
        }

        if (!run_mode_checked) {
            aclrtRunMode runMode;
            ret = aclrtGetRunMode(&runMode);
            if (!QueryAcl(ret, "aclrtGetRunMode")) {
                CleanupSessions();
                aclFinalize();
                delete infer;
                return nullptr;
            }
            run_mode_checked = true;
        }

        ret =
            aclmdlLoadFromFile(config->path_model_, &session.model_id_);
        if (!QueryAcl(ret, "aclmdlLoadFromFile")) {
            CleanupSessions();
            aclFinalize();
            delete infer;
            return nullptr;
        }

        session.model_desc_ = aclmdlCreateDesc();
        if (!session.model_desc_) {
            qDebug("Failed to create model description");
            CleanupSessions();
            aclFinalize();
            delete infer;
            return nullptr;
        }

        ret = aclmdlGetDesc(session.model_desc_, session.model_id_);
        if (!QueryAcl(ret, "aclmdlGetDesc")) {
            CleanupSessions();
            aclFinalize();
            delete infer;
            return nullptr;
        }

        QueryModelInfo(&session);
    }

    infer->initialized_ = true;
    qDebug("ACL contexts initialized successfully");
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
        if (session.context_) {
            aclrtDestroyContext(session.context_);
            session.context_ = nullptr;
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
        for (size_t idx = 0; idx < task->output_datasets_.size();
             ++idx) {
            aclmdlDataset* dataset = task->output_datasets_[idx];
            if (dataset) {
                if (idx < task->output_contexts_.size() &&
                    task->output_contexts_[idx]) {
                    QueryAcl(aclrtSetCurrentContext(
                                 task->output_contexts_[idx]),
                             "aclrtSetCurrentContext");
                }
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
        task->output_contexts_.clear();
        delete task;
    }
}

void Detect0(Infer* infer, InferTask* task, int no) {
    // 严格的参数校验
    if (!infer) {
        qDebug("ERROR: Infer context is null");
        throw std::runtime_error("Infer context is null");
    }

    if (!task) {
        qDebug("ERROR: Task is null");
        throw std::runtime_error("Task is null");
    }

    if (!task->image_) {
        qDebug("ERROR: Task image is null");
        throw std::runtime_error("Task image is null");
    }

    if (no < 0 || no >= static_cast<int>(infer->sessions_.size())) {
        qDebug("ERROR: Session index %d out of range [0, %zu)", no,
               infer->sessions_.size());
        throw std::runtime_error("Invalid session index");
    }

    if (task->image_->data_.isNull()) {
        qDebug("ERROR: Image data is null/empty");
        throw std::runtime_error("Image data is null");
    }

    if (task->image_->data_.format() != QImage::Format_RGB888) {
        qDebug("ERROR: Image format is %d, expected RGB888 (%d)",
               task->image_->data_.format(), QImage::Format_RGB888);
        throw std::runtime_error("Invalid image format");
    }

    auto& session = infer->sessions_[no];
    aclError set_ret = aclrtSetCurrentContext(session.context_);
    if (!QueryAcl(set_ret, "aclrtSetCurrentContext")) {
        throw std::runtime_error("Failed to set ACL context");
    }
    task->infer_ = no;
    int h = session.h_;
    int w = session.w_;

    // 验证模型维度
    if (h <= 0 || w <= 0) {
        qDebug("ERROR: Invalid model input dimensions: %dx%d", h, w);
        throw std::runtime_error("Invalid model input dimensions");
    }

    // 验证图像尺寸
    if (task->image_->data_.width() <= 0 ||
        task->image_->data_.height() <= 0) {
        qDebug("ERROR: Invalid image dimensions: %dx%d",
               task->image_->data_.width(),
               task->image_->data_.height());
        throw std::runtime_error("Invalid image dimensions");
    }

    qDebug("Processing image %dx%d with model input %dx%d",
           task->image_->data_.width(), task->image_->data_.height(), h,
           w);

    // 缩放图像到模型输入尺寸
    QImage scaled =
        ScalePadToRGB(task->image_->data_, w, h, task->scale_);
    const uchar* data = scaled.constBits();

    // 创建输入数据集
    aclmdlDataset* input_dataset = aclmdlCreateDataset();
    if (!input_dataset) {
        throw std::runtime_error("Failed to create input dataset");
    }

    // 验证输入数据
    if (!data) {
        qDebug("Input data is null");
        aclmdlDestroyDataset(input_dataset);
        throw std::runtime_error("Input data is null");
    }

    // 计算输入数据大小 (NCHW 格式: N=1, C=3, H=h, W=w) -
    // 使用float类型存储归一化数据
    size_t input_size = static_cast<size_t>(h) * w * 3 * sizeof(float);
    void* input_buffer = nullptr;
    aclError ret = aclrtMalloc(
        &input_buffer, input_size, ACL_MEM_MALLOC_NORMAL_ONLY);
    if (ret != ACL_SUCCESS) {
        qDebug("Failed to malloc input buffer, errorCode is %d", ret);
        aclmdlDestroyDataset(input_dataset);
        throw std::runtime_error("Failed to allocate input buffer");
    }

    // 将 HWC 格式转换为 NCHW 格式，并进行数据标准化
    // HWC: [H][W][3] -> NCHW: [1][3][H][W]
    auto* nchw_buffer = static_cast<float*>(input_buffer);
    size_t pixel_count = static_cast<size_t>(h) * w;

    // 分离 RGB 通道并按 NCHW 格式重新组织，同时进行0-1归一化
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            size_t hwc_idx = (y * w + x) * 3;

            // 验证数据索引
            if (hwc_idx + 2 >=
                static_cast<size_t>(scaled.sizeInBytes())) {
                qDebug("Data index out of bounds: %zu", hwc_idx + 2);
                aclrtFree(input_buffer);
                aclmdlDestroyDataset(input_dataset);
                throw std::runtime_error("Data index out of bounds");
            }

            // NCHW 索引计算
            size_t r_idx = y * w + x;  // R 通道: 位置 (y*w + x)
            size_t g_idx = pixel_count + y * w +
                           x;  // G 通道: 位置 (pixel_count + y*w + x)
            size_t b_idx = 2 * pixel_count + y * w +
                           x;  // B 通道: 位置 (2*pixel_count + y*w + x)

            // 将数据标准化到0-1范围并按NCHW格式存储
            nchw_buffer[r_idx] = static_cast<float>(data[hwc_idx]) /
                                 255.0f;  // R 分量归一化
            nchw_buffer[g_idx] = static_cast<float>(data[hwc_idx + 1]) /
                                 255.0f;  // G 分量归一化
            nchw_buffer[b_idx] = static_cast<float>(data[hwc_idx + 2]) /
                                 255.0f;  // B 分量归一化
        }
    }

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

    // 执行推理前的验证和调试信息
    qDebug(
        "About to execute model with input dataset (buffers: %zu) and "
        "output dataset (buffers: %zu)",
        aclmdlGetDatasetNumBuffers(input_dataset),
        aclmdlGetDatasetNumBuffers(output_dataset));

    // 显示输入数据的前几个像素作为验证
    float* input_data = static_cast<float*>(input_buffer);
    qDebug(
        "Input data sample - R:%.3f, G:%.3f, B:%.3f (first pixel "
        "normalized)",
        input_data[0], input_data[1], input_data[2]);

    // 验证模型ID
    qDebug(
        "Model ID: %u, Input dataset pointer: %p, Output dataset "
        "pointer: %p",
        session.model_id_, static_cast<void*>(input_dataset),
        static_cast<void*>(output_dataset));

    // 执行推理
    ret =
        aclmdlExecute(session.model_id_, input_dataset, output_dataset);
    if (!QueryAcl(ret, "aclmdlExecute")) {
        qDebug("aclmdlExecute failed with error code: %d", ret);
        qDebug(
            "This usually indicates invalid input parameters or data "
            "format issues");

        // 显示更多调试信息
        qDebug("Model input info - Height: %d, Width: %d", h, w);
        qDebug("Input data size: %zu bytes, Expected: %zu bytes",
               input_size,
               static_cast<size_t>(h) * w * 3 * sizeof(float));
        qDebug("Input dataset buffers: %zu",
               aclmdlGetDatasetNumBuffers(input_dataset));
        qDebug("Output dataset buffers: %zu",
               aclmdlGetDatasetNumBuffers(output_dataset));

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

    qDebug("Model execution successful! Verifying outputs...");

    // 验证输出数据
    size_t num_output_buffers =
        aclmdlGetDatasetNumBuffers(output_dataset);
    qDebug("Number of output buffers: %zu", num_output_buffers);

    for (size_t i = 0; i < num_output_buffers; ++i) {
        aclDataBuffer* buffer =
            aclmdlGetDatasetBuffer(output_dataset, i);
        if (buffer) {
            void* data = aclGetDataBufferAddr(buffer);
            size_t size = aclGetDataBufferSizeV2(buffer);
            qDebug("Output buffer %zu: %zu bytes", i, size);

            // 显示前几个浮点数值作为验证
            if (size >= sizeof(float) && data) {
                float* float_data = static_cast<float*>(data);
                qDebug(
                    "Output %zu sample values: [%.6f, %.6f, %.6f, "
                    "%.6f]",
                    i, float_data[0], float_data[1], float_data[2],
                    float_data[3]);
            }
        }
    }

    // 清理输入
    aclDestroyDataBuffer(input_buffer_desc);
    aclrtFree(input_buffer);
    aclmdlDestroyDataset(input_dataset);

    // 保存输出数据集供后续处理使用
    task->output_datasets_.push_back(output_dataset);
    task->output_contexts_.push_back(session.context_);

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

    int session_index = task->infer_;
    if (session_index < 0 ||
        session_index >= static_cast<int>(infer->sessions_.size())) {
        qDebug("Error: Invalid session index %d", session_index);
        return;
    }

    auto& session = infer->sessions_[session_index];
    aclError set_ret = aclrtSetCurrentContext(session.context_);
    if (!QueryAcl(set_ret, "aclrtSetCurrentContext")) {
        throw std::runtime_error("Failed to set ACL context");
    }

    // YOLO11 处理 9 个输出张量（3个尺度的box, score, score_sum）
    // 从 Ascend 模型获取输出信息
    size_t num_outputs = aclmdlGetNumOutputs(session.model_desc_);

    // 获取第一个（也是唯一的）输出数据集，其中包含所有9个缓冲区
    if (task->output_datasets_.size() < 1 ||
        !task->output_datasets_[0]) {
        qDebug("Error: No output dataset available");
        return;
    }

    aclmdlDataset* output_dataset = task->output_datasets_[0];
    size_t num_buffers = aclmdlGetDatasetNumBuffers(output_dataset);
    qDebug("Processing %zu output buffers from single dataset",
           num_buffers);

    for (size_t b = 0; b < num_outputs && b + 2 < 9; b += 3) {
        // 验证缓冲区索引
        if (b + 2 >= num_buffers) {
            qDebug(
                "Warning: Not enough output buffers. Need 3 starting "
                "from %zu, but only have %zu",
                b, num_buffers);
            continue;
        }

        // 获取 box 输出 (缓冲区 b)
        aclDataBuffer* box_buffer =
            aclmdlGetDatasetBuffer(output_dataset, b);
        if (!box_buffer) {
            qDebug("Failed to get box buffer for output %zu", b);
            continue;
        }
        void* box_ptr = aclGetDataBufferAddr(box_buffer);

        // 获取 score 输出 (缓冲区 b+1)
        float* score_ptr = nullptr;
        if (b + 1 < num_buffers) {
            aclDataBuffer* score_buffer =
                aclmdlGetDatasetBuffer(output_dataset, b + 1);
            if (score_buffer) {
                score_ptr = static_cast<float*>(
                    aclGetDataBufferAddr(score_buffer));
            }
        }

        // 获取 score_sum 输出 (缓冲区 b+2)
        float* score_sum_ptr = nullptr;
        if (b + 2 < num_buffers) {
            aclDataBuffer* score_sum_buffer =
                aclmdlGetDatasetBuffer(output_dataset, b + 2);
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
    for (size_t idx = 0; idx < task->output_datasets_.size(); ++idx) {
        aclmdlDataset* dataset = task->output_datasets_[idx];
        if (dataset) {
            if (idx < task->output_contexts_.size() &&
                task->output_contexts_[idx]) {
                QueryAcl(
                    aclrtSetCurrentContext(task->output_contexts_[idx]),
                    "aclrtSetCurrentContext");
            }
            size_t num_dataset_buffers =
                aclmdlGetDatasetNumBuffers(dataset);
            for (size_t i = 0; i < num_dataset_buffers; ++i) {
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
    task->output_contexts_.clear();

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
