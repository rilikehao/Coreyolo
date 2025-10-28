extern "C" {
#include "native.h"
}

#include <linux/videodev2.h>
#include <rknn_api.h>

#include <QPainter>

// im2d_version.h must be the first one
#include <rga/im2d_version.h>
// RGA includes
#include <rga/RockchipRga.h>
#include <rga/im2d_buffer.h>
#include <rga/im2d_single.h>
#include <rga/rga.h>

#include "image.h"
#include "inference.h"

constexpr int kScaleUsingGPU = 3;

struct Session {
    rknn_context context_ = 0;
    std::vector<rknn_tensor_attr> input_attrs_, output_attrs_;
    int h_, w_;
};

struct Infer {
    std::vector<std::string> names_;
    std::vector<Session> sessions_;
};

struct InferTask {
    Image* image_;
    std::vector<Detection> detections_;
    int infer_;
    float scale_;

    std::vector<rknn_output> outputs_;
};

namespace {

template <typename T>
bool QueryRknn(rknn_context context, rknn_query_cmd cmd, T& output) {
    return RKNN_SUCC == rknn_query(context, cmd, &output, sizeof(T));
}

bool QueryIoNum(rknn_context context, rknn_input_output_num& io_num) {
    return QueryRknn(context, RKNN_QUERY_IN_OUT_NUM, io_num);
}

bool QueryAttrs(rknn_context context, const char* type,
                rknn_query_cmd query_cmd,
                std::vector<rknn_tensor_attr>& attrs) {
    qDebug("%s tensors:", type);
    for (uint32_t i = 0; i < attrs.size(); i++) {
        attrs[i].index = i;
        if (!QueryRknn(context, query_cmd, attrs[i])) return false;
        qDebug("  %d(%s): dims=[%d, %d, %d, %d], zp=%d, scale=%f",
               attrs[i].index, attrs[i].name, attrs[i].dims[0],
               attrs[i].dims[1], attrs[i].dims[2], attrs[i].dims[3],
               attrs[i].zp, attrs[i].scale);
    }
    return true;
}

void QueryModelInfo(Session* s) {
    rknn_input_output_num io_num;
    QueryIoNum(s->context_, io_num);
    s->input_attrs_.resize(io_num.n_input);
    s->output_attrs_.resize(io_num.n_output);
    QueryAttrs(
        s->context_, "input", RKNN_QUERY_INPUT_ATTR, s->input_attrs_);
    QueryAttrs(s->context_, "output", RKNN_QUERY_OUTPUT_ATTR,
               s->output_attrs_);
    if (s->input_attrs_[0].fmt == RKNN_TENSOR_NCHW) {
        qDebug("model is NCHW input fmt");
        s->h_ = s->input_attrs_[0].dims[2];
        s->w_ = s->input_attrs_[0].dims[3];
    } else {
        qDebug("model is NHWC input fmt");
        s->h_ = s->input_attrs_[0].dims[1];
        s->w_ = s->input_attrs_[0].dims[2];
    }
    qDebug("model input height=%d, width=%d", s->h_, s->w_);
}

QImage ScalePadToRGBRga(QImage image, int w, int h, float& scale) {
    float scale_w = static_cast<float>(w) / image.width();
    float scale_h = static_cast<float>(h) / image.height();
    scale = std::min(scale_w, scale_h);
    int target_w = scale * image.width();
    int target_h = scale * image.height();
    QImage target(w, h, QImage::Format_RGB888);
    for (int i = 0; i < h; ++i) {
        memset(target.scanLine(i), kBgColor, target.bytesPerLine());
    }
    im_handle_param_t params;
    params.width = target.bytesPerLine() / 3;
    params.height = target.height();
    params.format = RK_FORMAT_RGB_888;
    auto dstHandle = importbuffer_virtualaddr(target.bits(), &params);
    auto dst =  //
        wrapbuffer_handle(dstHandle, target_w, target_h,
                          RK_FORMAT_RGB_888,  //
                          params.width, params.height);
    params.width = image.bytesPerLine() / 3;
    params.height = image.height();
    params.format = RK_FORMAT_RGB_888;
    auto srcHandle = importbuffer_virtualaddr(image.bits(), &params);
    auto src =  //
        wrapbuffer_handle(srcHandle, image.width(), image.height(),
                          RK_FORMAT_RGB_888,  //
                          params.width, params.height);
    imresize(src, dst);
    releasebuffer_handle(srcHandle);
    releasebuffer_handle(dstHandle);
    return target;
}

}  // namespace

Infer* CreateInfer(InferConfig* config) {
    auto infer = new Infer;
    InitNames(infer->names_, config->path_description_);
    infer->sessions_.resize(config->threads_);
    for (auto& i : infer->sessions_) {
        auto m = const_cast<char*>(config->path_model_);
        if (rknn_init(&i.context_, m, 0, 0, nullptr) < 0) {
            throw std::runtime_error("error load model");
        }
        QueryModelInfo(&i);
        qDebug("RKNN context initialized successfully");
    }
    return infer;
}

void DestroyInfer(Infer* infer) {
    for (auto& i : infer->sessions_) {
        if (rknn_destroy(i.context_) < 0) {
            throw std::runtime_error("error unload model");
        }
    }
}

struct InferTask* CreateInferTask() { return new InferTask; }
void DestroyInferTask(struct InferTask* task) { delete task; }

void Detect0(Infer* infer, InferTask* task, int no) {
    int h = infer->sessions_[no].h_, w = infer->sessions_[no].w_;
    QImage scaled =
        no < kScaleUsingGPU
            ? ScalePadToRGB(task->image_->data_, w, h, task->scale_)
            : ScalePadToRGBRga(task->image_->data_, w, h, task->scale_);
    const uchar* data = scaled.constBits();
    rknn_input input;
    input.index = 0;
    input.size = h * w * 3;
    input.buf = const_cast<uchar*>(data);
    input.pass_through = false;
    input.type = RKNN_TENSOR_UINT8;
    input.fmt = RKNN_TENSOR_NHWC;
    if (rknn_inputs_set(infer->sessions_[no].context_, 1, &input) < 0) {
        throw std::runtime_error("Failed to set input");
    }
    if (rknn_run(infer->sessions_[no].context_, nullptr) < 0) {
        throw std::runtime_error("Failed to run inference");
    }
    task->outputs_.resize(infer->sessions_[no].output_attrs_.size());
    for (int i = 0; i < task->outputs_.size(); i++) {
        task->outputs_[i].index = i;
        task->outputs_[i].want_float = true;
    }
    if (rknn_outputs_get(infer->sessions_[no].context_,
                         task->outputs_.size(), task->outputs_.data(),
                         nullptr) < 0) {
        throw std::runtime_error("Failed to get output");
    }
}

void Detect1(Infer* infer, InferTask* task) {
    PostProcessData data;
    data.names_ = &infer->names_;
    data.detections_ = &task->detections_;
    data.scale_ = task->scale_;
    for (int b = 0; b < 9; b += 3) {
        data.box_ = static_cast<float*>(task->outputs_[b].buf);
        data.score_ = static_cast<float*>(task->outputs_[b + 1].buf);
        data.score_sum_ =
            static_cast<float*>(task->outputs_[b + 2].buf);
        data.h_grid_ = infer->sessions_[0].output_attrs_[b].dims[2];
        data.w_grid_ = infer->sessions_[0].output_attrs_[b].dims[3];
        data.stride_ = infer->sessions_[0].h_ / data.h_grid_;
        PostProcess(data);
    }
    rknn_outputs_release(infer->sessions_[0].context_,
                         task->outputs_.size(), task->outputs_.data());
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
