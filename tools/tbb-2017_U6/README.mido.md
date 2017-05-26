TBB is a concurrency library for C++

The complete sources can be downloaded at https://github.com/01org/tbb.

For midonet, we have taken the source from release 2017_U6, compiled a
static library and included the includes and the static library in our
source tree.

To compile a static library for tbb, do "make extra_inc=big_iron.inc
tbb". The static library can then be found at
"build/linux_intel64_gcc_cc4.8_libc2.19_kernel3.13.0_release/libtbb.a".
Copy that file to linux-x86_64/.

TBB is licensed under the Apache License, version 2.
