Use the following steps to build Google Test.

1. Clone Google Test: https://github.com/google/googletest
2. Suppose you put Google Test in directory `${GTEST_DIR}`. To build it, create
a library build target (or a project as called by Visual Studio and Xcode) to
compile.
```
g++ -isystem ${GTEST_DIR}/include -I${GTEST_DIR} \
    -pthread -c ${GTEST_DIR}/src/gtest-all.cc
ar -rv libgtest.a gtest-all.o
```