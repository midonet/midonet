TBB is a concurrency library for C++

The complete sources can be downloaded at https://github.com/01org/tbb.

For midonet, we have taken the source from release 2017_U6, compiled a
shared library and included the includes and the shared library in our
source tree.

To compile a shared library for tbb, do "make clean tbb" from the tbb source code.

For linux:

The shared library can then be found at "build/linux_intel64_gcc_cc4.8_libc2.19_kernel3.13.0_release/libtbb.so.2".
Copy that file to $MIDONET_SRC/tools/tbb-2017_U6/linux-x86_64/, change directory into linux-x86_64, and create a symbolic link.

```
$ make clean tbb
...
$ cp build/linux_intel64_gcc_cc4.8_libc2.19_kernel3.13.0_release/libtbb.so.2 $MIDONET_SRC/tools/tbb-2017_U6/linux-x86_64/
$ cd $MIDONET_SRC/tools/tbb-2017_U6/linux-x84_64
$ ln -s libtbb.so.2 libtbb.so
```

For macos:

The shared library can then be found at "build/macos_intel64_clang_cc6.1.0_os10.10.3_release/libtbb.dylib".
Copy that file to $MIDONET_SRC/tools/tbb-2017_U6/osx-x86_64/. No need to symlink.

```
$ make clean tbb
...
$ cp build/macos_intel64_clang_cc6.1.0_os10.10.3_release/libtbb.dylib $MIDONET_SRC/tools/tbb-2017_U6/osx-x86_64
```

TBB is licensed under the Apache License, version 2.
