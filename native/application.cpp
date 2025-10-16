extern "C" {
#include "native.h"
}

#include <QApplication>

extern "C" {

void Main(int argc, char** argv, Func exec) {
    QApplication app(argc, argv);
    exec();
}

void Exec() {
    QApplication::instance()->exec();
}

}  // extern
