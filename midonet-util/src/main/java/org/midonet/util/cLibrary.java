/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.util;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import com.sun.jna.LastErrorException;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.Structure.ByValue;
import com.sun.jna.ptr.IntByReference;

/**
* JNA based library wrapper for a bunch of function calls inside the C library.
*/
public interface cLibrary extends Library {

    public static cLibrary lib = (cLibrary) Native.loadLibrary("c", cLibrary.class);

    public static class NetlinkSockAddress extends Structure {
        public short nl_family;
        public short nl_pad;
        public int nl_pid;
        public int nl_groups;

        @Override
        protected List getFieldOrder() {
            return Arrays.asList("nl_family", "nl_pad", "nl_pid", "nl_groups");
        }
    }

    public static class UnixPath extends Structure {
        public byte[] chars = new byte[108];

        @Override
        protected List getFieldOrder() {
            return Arrays.asList("chars");
        }
    }

    public static class UnixPathByVal extends UnixPath implements ByValue {}


    public static class UnixDomainSockAddress extends Structure {
        public short sun_family;
        public UnixPathByVal sun_path;

        @Override
        protected List getFieldOrder() {
            return Arrays.asList("sun_family", "sun_path");
        }
    }

    public static final int AF_UNIX = 1;
    public static final int AF_INET = 2;
    public static final int AF_INET6 = 10;
    public static final int AF_NETLINK = 16;

    public static final int SOCK_STREAM = 1;
    public static final int SOCK_DGRAM = 2;
    public static final int SOCK_RAW = 3;
    public static final int SOCK_RDM = 4;
    public static final int SOCK_SEQPACKET = 5;
    public static final int SOCK_DCCP = 6;
    public static final int SOCK_PACKET = 10;
    public static final int SOCK_CLOEXEC = 0x80000;
    public static final int SOCK_NONBLOCK = 0x800;

    // this is the default page size for an amd64 linux kernel
    public static int PAGE_SIZE = 0x1000;

    public static final int SOL_IP = 0;
    public static final int SOL_SOCKET = 1;
    public static final int SOL_TCP = 6;
    public static final int SOL_UDP = 17;
    public static final int SOL_IPV6 = 41;
    public static final int SOL_ICMPV6 = 58;
    public static final int SOL_RAW = 255;
    public static final int SOL_NETLINK = 270;

    public static final int SO_RCVBUF = 8;
    public static final int SO_RCVBUFFORCE = 33;

    public static final int NETLINK_ADD_MEMBERSHIP = 1;
    public static final int NETLINK_DROP_MEMBERSHIP = 2;
    public static final int NETLINK_BROADCAST_ERROR = 4;
    public static final int NETLINK_NO_ENOBUFS = 5;

    /* sys/mman.h */
    public static final int MCL_CURRENT = 1;
    public static final int MCL_FUTURE = 2;

    public static final int STDOUT_FILENO = 1;

    int mlockall(int flags) throws LastErrorException;

    int munlockall() throws LastErrorException;

    int socket(int domain, int type, int protocol);

    int connect(int fd, NetlinkSockAddress addrSockAddress, int size);

    int connect(int fd, UnixDomainSockAddress addrSockAddress, int size);

    int bind(int fd, NetlinkSockAddress addrSockAddress, int size);

    int bind(int fd, UnixDomainSockAddress addrSockAddress, int size);

    int accept(int fd, UnixDomainSockAddress clientSockAddress, IntByReference size);

    int listen(int fd, int backlog);

    int getsockname(int fd, NetlinkSockAddress addrSockAddress, IntByReference size);

    int getsockname(int fd, UnixDomainSockAddress addrSockAddress, IntByReference size);

    int setsockopt(int fd, int level, int optname, ByteBuffer buf, int buflen);
    int getsockopt(int fd, int level, int optname, ByteBuffer buf, ByteBuffer buflen);

    int send(int fd, ByteBuffer buf, int len, int flags);

    int recv(int fd, ByteBuffer buf, int len, int flags);

    int getpagesize();

    int close(long l);

    String strerror(int errno);

    int isatty(int fd);
}
