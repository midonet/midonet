/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.netlink.clib;

import java.nio.ByteBuffer;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;
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
    }

    public static final int AF_NETLINK = 16;
    public static final int SOCK_RAW = 3;

    // this is the default page size for an amd64 linux kernel
    public static int PAGE_SIZE = 0x1000;

    static final int SOL_NETLINK = 270;

    static final int NETLINK_ADD_MEMBERSHIP = 1;
    static final int NETLINK_DROP_MEMBERSHIP = 2;

    int socket(int domain, int type, int protocol);

    int connect(int fd, NetlinkSockAddress addrSockAddress, int size);

    int bind(int fd, NetlinkSockAddress addrSockAddress, int size);

    int getsockname(int fd, NetlinkSockAddress addrSockAddress, IntByReference size);

    int setsockopt(int fd, int level, int optname, ByteBuffer buf, IntByReference optlen);

    int send(int fd, ByteBuffer buf, int len, int flags);

    int recv(int fd, ByteBuffer buf, int len, int flags);

    int getpagesize();

    String strerror(int errno);
}
