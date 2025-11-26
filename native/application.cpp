extern "C" {
#include "native.h"
}

#include <QGuiApplication>

#include "launcher.h"

extern "C" {

void Main(int argc, char** argv, Func exec) {
    InitProcessLauncher();
    QGuiApplication app(argc, argv);
    exec();
}

void Exec() { QGuiApplication::instance()->exec(); }

}  // extern
