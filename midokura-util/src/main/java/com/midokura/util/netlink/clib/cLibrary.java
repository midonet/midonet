/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.clib;

import java.nio.ByteBuffer;

import com.sun.jna.Library;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;

/**
* // TODO: Explain yourself.
*
* @author Mihai Claudiu Toader <mtoader@midokura.com>
*         Date: 7/2/12
*/
public interface cLibrary extends Library {

    public static class NetlinkSockAddress extends Structure {
        public short nl_family;
        public short nl_pad;
        public int nl_pid;
        public int nl_groups;
    }

    public static final int AF_NETLINK = 16;
    public static final int SOCK_RAW = 3;

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

    String strerror(int errno);
}
