#ifndef NATIVE_H
#define NATIVE_H

#include <stdbool.h>
#include <stdint.h>

typedef void (*Func)();

void Main(int argc, char** argv, Func exec);
void Exec();

bool SupportFormat(uint32_t v4l2_format);

struct Image;

struct Image* CreateImage(void* data, int w, int h, uint32_t format);
struct Image* CreateImagePath(const char* path);
struct Image* CreateImageRGB24(int w, int h);
void DestroyImage(struct Image* image);
int BytesPerLine(struct Image* image);
uint8_t* Bits(struct Image* image);

struct InferConfig {
    char *path_description_, *path_model_;
    int threads_;
};

struct Infer;

struct Infer* CreateInfer(struct InferConfig* config);
void DestroyInfer(struct Infer* infer);

struct Rect {
    int x0_, x1_, y0_, y1_;
};

struct Detection {
    struct Rect bound_;
    const char* name_;
    float score_;
};

struct InferTask;

struct InferTask* CreateInferTask();
void DestroyInferTask(struct InferTask* task);
void Detect0(struct Infer* infer, struct InferTask* task, int no);
void Detect1(struct Infer* infer, struct InferTask* task);

// task 将放弃 image 所有权
struct Image* GetImage(struct InferTask* task);

// task 将拥有 image 所有权
void SetImage(struct InferTask* task, struct Image* image);

int GetWidth(struct Image* image);
int GetHeight(struct Image* image);

int SizeDetections(struct InferTask* task);
struct Detection* PtrDetections(struct InferTask* task);
const char* GetError(struct InferTask* task);

struct Output;

struct Output* CreateOutput();
void DestroyOutput(struct Output* out);

// 调用者将放弃 image 所有权
void SendToOutput(struct Output* out, struct Image* image);

void DrawRect(                               //
    struct Image* image, struct Rect* rect,  //
    int r, int g, int b, const char* s);

void HttpGet(const char* url);

#endif  // NATIVE_H
