extern "C" {
#include "native.h"
}

#include <QGuiApplication>

extern "C" {

void Main(int argc, char** argv, Func exec) {
    QGuiApplication app(argc, argv);
    exec();
}

void Exec() { QGuiApplication::instance()->exec(); }

}  // extern
