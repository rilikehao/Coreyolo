extern "C" {
#include "native.h"
}

#include <QCoreApplication>

extern "C" {

void Main(int argc, char** argv, Func exec) {
    QCoreApplication app(argc, argv);
    exec();
}

void Exec() { QCoreApplication::instance()->exec(); }

}  // extern
