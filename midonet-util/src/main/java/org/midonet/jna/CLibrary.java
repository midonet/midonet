/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.jna;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.ByValue;
import com.sun.jna.ptr.IntByReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JNA based library wrapper for a bunch of function calls inside the C library.
 */
@SuppressWarnings("unused")
public final class CLibrary {

    private static final Logger log = LoggerFactory.getLogger("org.midonet.jna");

    static {
        try {
            Native.register(Platform.C_LIBRARY_NAME);
        } catch (NoClassDefFoundError | UnsatisfiedLinkError | NoSuchMethodError e) {
            log.error("Native method calls are not available");
            System.exit(-1);
        }
    }

    private CLibrary() {}

    public static class NetlinkSockAddress extends Structure {
        public short nl_family;
        public short nl_pad;
        public int nl_pid;
        public int nl_groups;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("nl_family", "nl_pad", "nl_pid", "nl_groups");
        }
    }

    public static class UnixPath extends Structure {
        public byte[] chars = new byte[108];

        @Override
        protected List<String> getFieldOrder() {
            return Collections.singletonList("chars");
        }
    }

    public static class UnixPathByVal extends UnixPath implements ByValue {}

    public static class UnixDomainSockAddress extends Structure {
        public short sun_family;
        public UnixPathByVal sun_path;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("sun_family", "sun_path");
        }
    }

    public static final int SOCK_STREAM = 1;
    public static final int SOCK_DGRAM = 2;
    public static final int SOCK_RAW = 3;
    public static final int SOCK_RDM = 4;
    public static final int SOCK_SEQPACKET = 5;
    public static final int SOCK_DCCP = 6;
    public static final int SOCK_PACKET = 10;
    public static final int SOCK_CLOEXEC = 0x80000;
    public static final int SOCK_NONBLOCK = 0x800;

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

    public static final int MCL_CURRENT = 1;
    public static final int MCL_FUTURE = 2;

    public static final int STDOUT_FILENO = 1;

    /**
     * Causes all of the pages mapped by the address space of a process to be
     * memory-resident until unlocked or until the process exits or execs
     * another process image.
     * @param flags Determines whether the pages to be locked are those
     *              currently mapped by the address space of the process,
     *              those that are mapped in the future, or both.
     *              MCL_CURRENT Lock all of the pages currently mapped into the
     *              address space of the process.
     *              MCL_FUTURE  Lock all of the pages that become mapped into
     *              the address space of the process in the future, when those
     *              mappings are established.
     * @return Zero, if the method is successful. On error, it throws a
     * {@code LastErrorException}.
     */
    public static native int mlockall(int flags) throws LastErrorException;

    /**
     * Unlocks the address space of a process.
     * @return Zero, if the method is successful. On error, it throws a
     * {@code LastErrorException}.
     */
    public static native int munlockall() throws LastErrorException;

    /**
     * Manipulates the underlying device parameters for special files.
     * @param fd The device file descriptor.
     * @param request The device-dependent request code.
     * @param args Arguments for this request.
     * @return Zero, if the method is successful. On error, it throws a
     * {@code LastErrorException}.
     */
    public static native int ioctl(int fd, long request, Pointer args)
        throws LastErrorException;

    /**
     * Creates an endpoint for communication and returns a file descriptor that
     * refers to that endpoint.
     * @param domain The protocol family:
     *               AF_UNIX Local communication
     *               AF_INET IPv4
     *               AF_INET6 IPv6
     *               AF_NETLINK Kernel user interface device
     * @param type The communication semantics:
     *             SOCK_STREAM Sequenced, reliable, connection-based streams
     *             SOCK_DGRAM Connectionless, unreliable datagrams
     *             SOCK_RAW Raw network protocol
     *             SOCK_RDM Reliable datagrams
     *             SOCK_SEQPACKET Sequenced, reliable datagrams
     *             SOCK_PACKET Obsolete
     *             The values above can be flagged with a bitwise OR to modify
     *             the socket behavior:
     *             SOCK_NONBLOCK - Non-blocking flag
     *             SOCK_CLOEXEC Close-on-exec flag
     * @param protocol The protocol for the specified family and type.
     * @return A file descriptor for the new socket. On error, it throws a
     * {@code LastErrorException}.
     */
    public static native int socket(int domain,
                                    int type,
                                    int protocol) throws LastErrorException;

    /**
     * Connects the socket specified by the file descriptor to the specified
     * Netlink socket address.
     * @param fd The socket file descriptor.
     * @param addrSockAddress The Netlink socket address.
     * @param size The size of the socket address structure.
     * @return Zero, if the method is successful. On error, it returns -1 and
     * errno indicates the last error.
     */
    public static native int connect(int fd,
                                     NetlinkSockAddress addrSockAddress,
                                     int size);

    /**
     * Connects the socket specified by the file descriptor to the specified
     * Unix domain socket address.
     * @param fd The socket file descriptor.
     * @param addrSockAddress The Unix domain socket address.
     * @param size The size of the socket address structure.
     * @return Zero, if the method is successful. On error, it returns -1 and
     * errno indicates the last error.
     */
    public static native int connect(int fd,
                                     UnixDomainSockAddress addrSockAddress,
                                     int size);

    /**
     * Assigns the specified Netlink socket address to the socket referred by
     * the file descriptor.
     * @param fd The socket file descriptor.
     * @param addrSockAddress The Netlink socket address.
     * @param size The size of the socket address structure.
     * @return Zero, if the method is successful. On error, it returns -1 and
     * errno indicates the last error.
     */
    public static native int bind(int fd,
                                  NetlinkSockAddress addrSockAddress,
                                  int size);

    /**
     * Assigns the specified Unix domain socket address to the socket referred
     * by the file descriptor.
     * @param fd The socket file descriptor.
     * @param addrSockAddress The Unix domain socket address.
     * @param size The size of the socket address structure.
     * @return Zero, if the method is successful. On error, it returns -1 and
     * errno indicates the last error.
     */
    public static native int bind(int fd,
                                  UnixDomainSockAddress addrSockAddress,
                                  int size);

    /**
     * Used with connection-based socket types. It returns a file descriptor
     * with the first connection request on the queue of pending connections
     * for the listening socket.
     * @param fd The listening socket file descriptor.
     * @param clientSockAddress The methods sets this reference to the peer
     *                          socket address.
     * @param size The method sets this reference to the peer socket address
     *             structure size.
     * @return The file descriptor of the accepted socket, if successful. On
     * error, it returns -1 and errno indicates the last error.
     */
    public static native int accept(int fd,
                                    UnixDomainSockAddress clientSockAddress,
                                    IntByReference size);

    /**
     * Listens on incoming connections on the specified socket.
     * @param fd The socket file descriptor.
     * @param backlog The maximum length of the incomming connection queue. When
     *                the queue is full, incoming connections receive an error
     *                with ECONNREFUSED.
     * @return Zero, if the method is successful. On error, it returns -1 and
     * errno indicates the last error.
     */
    public static native int listen(int fd,
                                    int backlog);

    /**
     * Returns the address to which the specified Netlink socket is bound.
     * @param fd The socket file descriptor.
     * @param addrSockAddress The methods sets this reference to the socket
     *                        address.
     * @param size The method sets this reference to the socket address
     *             structure size.
     * @return Zero, if the method is successful. On error, it returns -1 and
     * errno indicates the last error.
     */
    public static native int getsockname(int fd,
                                         NetlinkSockAddress addrSockAddress,
                                         IntByReference size);

    /**
     * Returns the address to which the specified Unix domain socket is bound.
     * @param fd The socket file descriptor.
     * @param addrSockAddress The methods sets this reference to the socket
     *                        address.
     * @param size The method sets this reference to the socket address
     *             structure size.
     * @return Zero, if the method is successful. On error, it returns -1 and
     * errno indicates the last error.
     */
    public static native int getsockname(int fd,
                                         UnixDomainSockAddress addrSockAddress,
                                         IntByReference size);

    /**
     * Sets options for the specified socket.
     * @param fd The socket file descriptor.
     * @param level The level at which the option resides.
     * @param optname The option name, interpreted by the appropriate protocol.
     * @param optval The option value.
     * @param optlen The option value length.
     * @return Zero, if the method is successful. On error, it returns -1 and
     * errno indicates the last error.
     */
    public static native int setsockopt(int fd,
                                        int level,
                                        int optname,
                                        ByteBuffer optval,
                                        int optlen);

    /**
     * Gets options for the specified socket.
     * @param fd The socket file descriptor.
     * @param level The level at which the option resides.
     * @param optname The level at which the option resides.
     * @param optval The method sets this to the option value.
     * @param optlen The method sets this to the option value length.
     * @return Zero, if the method is successful. On error, it returns -1 and
     * errno indicates the last error.
     */
    public static native int getsockopt(int fd,
                                        int level,
                                        int optname,
                                        ByteBuffer optval,
                                        ByteBuffer optlen);

    /**
     * Sends data to a connected socket.
     * @param fd The socket file descriptor.
     * @param buf The data to send.
     * @param len The data length.
     * @param flags Operation flags, see:
     *              http://man7.org/linux/man-pages/man2/send.2.html
     * @return The number of bytes sent, if successful. On error, it returns -1
     * and errno indicates the last error.
     */
    public static native int send(int fd,
                                  ByteBuffer buf,
                                  int len,
                                  int flags);

    /**
     * Receives data from a connected socket.
     * @param fd The socket file descriptor.
     * @param buf The method sets this buffer with the received data.
     * @param len The buffer size.
     * @param flags Operations flags, see:
     *              http://man7.org/linux/man-pages/man2/recv.2.html
     * @return The number of bytes received. if successful. On error, it returns
     * -1 and errno indicates the last error.
     */
    public static native int recv(int fd,
                                  ByteBuffer buf,
                                  int len,
                                  int flags);

    /**
     * Returns the number of bytes in a memory page.
     */
    public static native int getpagesize();

    /**
     * Closes a file descriptor.
     */
    public static native int close(int fd);

    /**
     * Returns the string describing the error number.
     */
    public static native String strerror(int errno);

    /**
     * Tests whether the file descriptor is a terminal.
     * @return 1, if the open file descriptor is a terminal, 0 otherwise and
     * errno is set to indicate the error.
     */
    public static native int isatty(int fd);

}
