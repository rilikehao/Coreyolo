extern "C" {
#include "native.h"
}

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
#include <linux/videodev2.h>

#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <QPainter>

#include "image.h"
#include "inference.h"

struct Infer {
    MNN::Interpreter* interpreter_ = nullptr;
    std::vector<std::string> names_;
    std::string input_name_;
    std::vector<std::string> output_names_;
    int h_ = 0, w_ = 0;
    std::vector<MNN::Session*> sessions_;
};

struct InferTask {
    Image* image_;
    std::vector<Detection> detections_;
    int infer_;
    float scale_;

    std::vector<std::unique_ptr<MNN::Tensor>> outputs_;
};

namespace {

int Suffix(const std::string& s) {
    int k = s.size() - 1;
    while (0 <= k && isdigit(s[k])) --k;
    return stol(s.substr(k + 1));
}

void QueryModelInfo(Infer* infer) {
    MNN::ScheduleConfig config;
    config.type = MNN_FORWARD_VULKAN;
    auto session = infer->interpreter_->createSession(config);
    auto inputs = infer->interpreter_->getSessionInputAll(session);
    for (const auto& pair : inputs) infer->input_name_ = pair.first;
    auto outputs = infer->interpreter_->getSessionOutputAll(session);
    std::map<int, std::string> m;
    for (const auto& pair : outputs) {
        m[Suffix(pair.first)] = pair.first;
    }
    for (const auto& pair : m) {
        infer->output_names_.emplace_back(pair.second);
    }
    auto input_tensor = infer->interpreter_->getSessionInput(
        session, infer->input_name_.c_str());
    infer->h_ = input_tensor->height();
    infer->w_ = input_tensor->width();
    infer->interpreter_->releaseSession(session);
}

}  // namespace

extern "C" {

Infer* CreateInfer(InferConfig* config) {
    auto infer = new Infer;
    InitNames(infer->names_, config->path_description_);
    infer->interpreter_ =
        MNN::Interpreter::createFromFile(config->path_model_);
    if (!infer->interpreter_) {
        throw std::runtime_error("error load model");
    }
    QueryModelInfo(infer);
    qDebug("MNN context initialized successfully");
    infer->sessions_.resize(config->threads_);
    MNN::ScheduleConfig mnn_config;
    mnn_config.type = MNN_FORWARD_VULKAN;
    for (auto& i : infer->sessions_) {
        i = infer->interpreter_->createSession(mnn_config);
    }
    return infer;
}

void DestroyInfer(Infer* infer) {
    for (auto& i : infer->sessions_) {
        if (!infer->interpreter_->releaseSession(i)) {
            throw std::runtime_error("error unload model");
        }
    }
    MNN::Interpreter::destroy(infer->interpreter_);
}

struct InferTask* CreateInferTask() { return new InferTask; }
void DestroyInferTask(struct InferTask* task) { delete task; }

void Detect0(Infer* infer, InferTask* task, int no) {
    auto interpreter = infer->interpreter_;
    MNN::ScheduleConfig config;
    config.type = MNN_FORWARD_VULKAN;
    auto input_tensor = interpreter->getSessionInput(
        infer->sessions_[no], infer->input_name_.c_str());
    int w = infer->w_;
    int h = infer->h_;
    QImage scaled =
        ScalePadToRGB(task->image_->data_, w, h, task->scale_);
    MNN::Tensor data(input_tensor);
    auto input = data.host<float>();
    const uchar* imageData = scaled.constBits();
    for (int i = 0; i < h * w; ++i) {
        input[i] = imageData[i * 3] / 255.0f;
        input[h * w + i] = imageData[i * 3 + 1] / 255.0f;
        input[2 * h * w + i] = imageData[i * 3 + 2] / 255.0f;
    }
    input_tensor->copyFromHostTensor(&data);
    if (interpreter->runSession(infer->sessions_[no]) !=
        MNN::NO_ERROR) {
        throw std::runtime_error("Failed to run inference");
    }
    std::vector<MNN::Tensor*> output_tensors;
    for (const auto& i : infer->output_names_) {
        auto tensor = interpreter->getSessionOutput(
            infer->sessions_[no], i.c_str());
        output_tensors.emplace_back(tensor);
    }
    for (auto i : output_tensors) {
        task->outputs_.emplace_back(std::make_unique<MNN::Tensor>(i));
        i->copyToHostTensor(task->outputs_.back().get());
    }
    output_tensors.clear();
}

void Detect1(Infer* infer, InferTask* task) {
    PostProcessData data;
    data.names_ = &infer->names_;
    data.detections_ = &task->detections_;
    data.scale_ = task->scale_;
    for (int b = 0; b < 9; b += 3) {
        data.box_ = task->outputs_[b]->host<float>();
        data.score_ = task->outputs_[b + 1]->host<float>();
        data.score_sum_ = task->outputs_[b + 2]->host<float>();
        data.h_grid_ = task->outputs_[b]->height();
        data.w_grid_ = task->outputs_[b]->width();
        data.stride_ = infer->h_ / data.h_grid_;
        PostProcess(data);
    }
    task->outputs_.clear();
    NonMaximumSuppression(task->detections_);
}

struct Image* GetImage(struct InferTask* task) { return task->image_; }

void SetImage(struct InferTask* task, struct Image* image) {
    task->image_ = image;
}

int SizeDetections(InferTask* task) { return task->detections_.size(); }

Detection* PtrDetections(InferTask* task) {
    return task->detections_.data();
}

}  // extern
