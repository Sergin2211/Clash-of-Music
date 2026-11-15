#include <stdlib.h>

int Py_BytesMain(int argc, char **argv);

int main(int argc, char **argv)
{
    if (setenv("PYTHONUTF8", "1", 1) != 0) {
        return EXIT_FAILURE;
    }

    return Py_BytesMain(argc, argv);
}
