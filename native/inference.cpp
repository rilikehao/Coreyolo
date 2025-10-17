#include "inference.h"

#include <QDebug>
#include <QFile>
#include <QPainter>
#include <cmath>
#include <unordered_map>
#include <utility>

namespace {

void DflRectF(float* d, float& x0, float& x1, float& y0, float& y1) {
    float box[4];
    for (int b = 0; b < 4; b++) {
        float exp_sum = 0;
        float acc_sum = 0;
        for (int i = 0; i < kSizeDfl; i++) {
            float exp_val = std::exp(d[b * kSizeDfl + i]);
            exp_sum += exp_val;
            acc_sum += exp_val * i;
        }
        box[b] = acc_sum / exp_sum;
    }
    x0 = 0.5 - box[0];
    x1 = 0.5 + box[2];
    y0 = 0.5 - box[1];
    y1 = 0.5 + box[3];
}

float CalculateIoU(const Rect& box0, const Rect& box1) {
    int x0 = std::max(box0.x0_, box1.x0_);
    int x1 = std::min(box0.x1_, box1.x1_);
    int y0 = std::max(box0.y0_, box1.y0_);
    int y1 = std::min(box0.y1_, box1.y1_);
    int intersection = std::max(0, x1 - x0) * std::max(0, y1 - y0);
    int area1 = (box0.x1_ - box0.x0_) * (box0.y1_ - box0.y0_);
    int area2 = (box1.x1_ - box1.x0_) * (box1.y1_ - box1.y0_);
    int union_area = area1 + area2 - intersection;
    if (union_area < 0) return 0.0;
    return static_cast<float>(intersection) / union_area;
}

}  // namespace

void InitNames(std::vector<std::string>& names, const char* path) {
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly | QIODevice::Text)) return;
    while (!file.atEnd()) {
        std::string s = file.readLine().trimmed().toStdString();
        if (s.empty()) break;
        names.emplace_back(s);
    }
    qDebug("Categories' names initialized successfully");
}

QImage ScalePadToRGB(QImage image, int w, int h, float& scale) {
    QImage scaled =
        image.scaled(w, h, Qt::KeepAspectRatio, Qt::FastTransformation);
    QImage target(w, h, QImage::Format_RGB888);
    target.fill(QColor(kBgColor, kBgColor, kBgColor));
    QPainter painter(&target);
    painter.drawImage(0, 0, scaled);
    painter.end();
    float scale_h = static_cast<float>(h) / image.height();
    float scale_w = static_cast<float>(w) / image.width();
    scale = std::min(scale_h, scale_w);
    return target;
}

void PostProcess(PostProcessData& data) {
    for (int h = 0; h < data.h_grid_; h++) {
        for (int w = 0; w < data.w_grid_; w++) {
            int offset = h * data.w_grid_ + w;
            if (data.score_sum_[offset] < kConfThreshold) continue;
            int hw = data.h_grid_ * data.w_grid_;
            float max_score = 0.0;
            int argmax_score = -1;
            for (int i = 0; i < data.names_->size(); i++) {
                float score = data.score_[offset + hw * i];
                if (max_score < score) {
                    max_score = score;
                    argmax_score = i;
                }
            }
            if (kConfThreshold < max_score) {
                float box[64], x0, x1, y0, y1;
                for (int i = 0; i < 64; ++i) {
                    box[i] = data.box_[offset + hw * i];
                }
                DflRectF(box, x0, x1, y0, y1);
                data.detections_->emplace_back();
                data.detections_->back().name_ =
                    data.names_->at(argmax_score).c_str();
                data.detections_->back().score_ = max_score;
                Rect& r = data.detections_->back().bound_;
                r.x0_ = data.stride_ / data.scale_ * (x0 + w);
                r.x1_ = data.stride_ / data.scale_ * (x1 + w);
                r.y0_ = data.stride_ / data.scale_ * (y0 + h);
                r.y1_ = data.stride_ / data.scale_ * (y1 + h);
            }
        }
    }
}

void NonMaximumSuppression(std::vector<Detection>& detections) {
    if (detections.size() <= 1) return;
    std::unordered_map<std::string, std::vector<int>> class_groups;
    for (int i = 0; i < detections.size(); ++i) {
        class_groups[detections[i].name_].emplace_back(i);
    }
    std::vector<char> suppressed(detections.size());
    for (auto& pair : class_groups) {
        std::vector<int>& indices = pair.second;
        std::sort(indices.begin(), indices.end(), [&](int i0, int i1) {
            return detections[i1].score_ < detections[i0].score_;
        });
        for (int i = 0; i < indices.size(); ++i) {
            int idx_i = indices[i];
            if (suppressed[idx_i]) continue;
            for (int j = i + 1; j < indices.size(); ++j) {
                int idx_j = indices[j];
                if (suppressed[idx_j]) continue;
                float iou = CalculateIoU(
                    detections[idx_i].bound_, detections[idx_j].bound_);
                if (iou > kNmsThreshold) suppressed[idx_j] = true;
            }
        }
    }
    std::vector<int> indices;
    std::vector<Detection> filtered;
    for (int i = 0; i < detections.size(); ++i) {
        if (!suppressed[i]) indices.emplace_back(i);
    }
    std::sort(indices.begin(), indices.end(), [&](int i0, int i1) {
        return detections[i1].score_ < detections[i0].score_;
    });
    for (int i : indices) {
        filtered.emplace_back(std::move(detections[i]));
    }
    std::swap(detections, filtered);
}
