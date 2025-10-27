#ifndef INFERENCE_H
#define INFERENCE_H

extern "C" {
#include "native.h"
}

#include <QElapsedTimer>
#include <QImage>
#include <memory>
#include <string>
#include <vector>

constexpr int kBgColor = 114;
constexpr int kSizeDfl = 16;
constexpr float kConfThreshold = 0.25;
constexpr float kNmsThreshold = 0.45;

struct PostProcessData {
    const std::vector<std::string>* names_;
    int h_grid_, w_grid_, stride_;
    float *box_, *score_, *score_sum_, scale_;
    std::vector<Detection>* detections_;
};

void InitNames(std::vector<std::string>& names, const char* path);
void PostProcess(PostProcessData& data);
void NonMaximumSuppression(std::vector<Detection>& detections);

#endif
