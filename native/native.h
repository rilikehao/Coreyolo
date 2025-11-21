#ifndef NATIVE_H
#define NATIVE_H

#include <stdbool.h>
#include <stdint.h>

typedef void (*Func)();

void Main(int argc, char** argv, Func exec);
void Exec();

struct Image;

struct Image* CreateImageRGB24(int w, int h);
struct Image* CreateImageJPEG(void* data, int max_size);
struct Image* CreateImagePath(const char* path);
struct Image* CreateImageCopy(struct Image* origin);

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

// 在调用之前要求用户先删除里面的 Image
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

void DrawRect(                               //
    struct Image* image, struct Rect* rect,  //
    int r, int g, int b, const char* s);

void HttpGet(const char* url);
void HttpPost(const char* url, struct Image* image);

int HttpGetWaitStatus(const char* url);

struct TcpSocket;
bool SendData(struct TcpSocket* socket, const char* data, int size);

struct Subscribe {
    void (*func_)(const char* stream, struct TcpSocket*, void*,  //
                  double begin, double end, bool fast);
    void* opaque_;
};

struct HttpServerThread;
struct HttpServerThread* HttpServer(int port, struct Subscribe sub);
void StopHttpServer(struct HttpServerThread* thread);

#endif  // NATIVE_H
