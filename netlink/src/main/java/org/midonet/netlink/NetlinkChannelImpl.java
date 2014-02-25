/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.netlink;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.spi.SelectorProvider;

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.nio.ch.SelChImpl;

import org.midonet.netlink.clib.cLibrary;
import org.midonet.netlink.hacks.IOUtil;

/**
 * Package private implementation of a NetlinkChannel.
 */
class NetlinkChannelImpl extends NetlinkChannel implements SelChImpl {

    private static final Logger log = LoggerFactory
        .getLogger(NetlinkChannelImpl.class);

    /* Set the RCVBUF size to 2MB.
     *
     * Since we are disabling ENOBUFS errors below, a bigger buffer size cannot
     * hurt us. Had we not, a big size could cause msgs to be dropped for too
     * long because the error condition is only cleared when userspace depletes
     * the read buffer.
     *
     * With that out of the way, a bigger buffer gives userspace a bit more
     * room to fall behind and then catch up without losing messages under
     * heavy traffic/load.
     */
    private final static int RCVBUF_SIZE = 2 * 1024 * 1024;

    public NetlinkChannelImpl(SelectorProvider provider,
                              Netlink.Protocol protocol) {
        super(provider, protocol);
        this.state = ST_UNCONNECTED;

        int socket = cLibrary.lib.socket(cLibrary.AF_NETLINK, cLibrary.SOCK_RAW,
                                         protocol.getValue());

        if (socket == -1) {
            log.error("Could not create netlink socket: {}",
                      cLibrary.lib.strerror(Native.getLastError()));
        }

        fd = IOUtil.newFD(socket);
        fdVal = IOUtil.fdVal(fd);

        ByteBuffer sobuf = ByteBuffer.allocate(4);
        sobuf.order(ByteOrder.LITTLE_ENDIAN);
        sobuf.putInt(RCVBUF_SIZE);

        int sockoptret = cLibrary.lib.setsockopt(
            fdVal, cLibrary.SOL_SOCKET, cLibrary.SO_RCVBUFFORCE, sobuf, 4);
        if (sockoptret != 0) {
            log.error("SETSOCKOPT failed: {}",
                cLibrary.lib.strerror(Native.getLastError()));
        } else {
            log.info("Successfully set netlink channel RCVBUFF size to {}",
                     RCVBUF_SIZE);
        }

        /* Set NETLINK_BROADCAST_ERROR to 1.
         * See http://patchwork.ozlabs.org/patch/23338/
         *
         * This option tells netlink to let callers of netlink_broadcast()
         * know about delivery errors (that's delivery to userspace through
         * the connection we are setting up here). Note that OVS uses
         * netlink_broadcast() to send to userspace, so this will make sure it
         * gets the errors (and ignore them at will).
         */
        ByteBuffer sobuf2 = ByteBuffer.allocate(4);
        sobuf2.order(ByteOrder.LITTLE_ENDIAN);
        sobuf2.putInt(1);
        sockoptret = cLibrary.lib.setsockopt(
            fdVal, cLibrary.SOL_NETLINK, cLibrary.NETLINK_BROADCAST_ERROR, sobuf2, 4);
        if (sockoptret != 0) {
            log.error("SETSOCKOPT NETLINK_BROADCAST_ERROR failed: {}",
                    cLibrary.lib.strerror(Native.getLastError()));
        } else {
            log.debug("SETSOCKOPT success: NETLINK_BROADCAST_ERROR");
        }

        /* Set NETLINK_NO_ENOBUFS to 1.
         * See http://kerneltrap.org/mailarchive/linux-netdev/2009/3/23/5223184
         *
         * This option tells netlink to ignore ENOBUFS errors while delivering
         * to this socket. Users of this connection need to be resilient
         * to dropped messages. Note that having ENOBUFS delivered would not
         * be of much help because it doesn't provide enough information
         * to do anything meaningful other than 'resync all state'. Users can
         * be more clever and track the requests that time out individually.
         *
         * This should also help throughput. With ENOBUFS enabled, the kernel
         * will wait for userspace to deplete the read buffer before writing
         * again to it, causing spikes in throughput.
         */
        sockoptret = cLibrary.lib.setsockopt(
                fdVal, cLibrary.SOL_NETLINK, cLibrary.NETLINK_NO_ENOBUFS, sobuf2, 4);
        if (sockoptret != 0) {
            log.error("SETSOCKOPT NETLINK_NO_ENOBUFS failed: {}",
                    cLibrary.lib.strerror(Native.getLastError()));
        } else {
            log.debug("SETSOCKOPT success: NETLINK_NO_ENOBUFS");
        }
    }

    protected void _executeConnect(Netlink.Address address) throws IOException {
        cLibrary.NetlinkSockAddress remote = new cLibrary.NetlinkSockAddress();
        remote.nl_family = cLibrary.AF_NETLINK;
        remote.nl_pid = address.getPid();

        if (cLibrary.lib
                    .connect(fdVal, remote, remote.size()) < 0) {
            throw
                new IOException("failed to connect to socket: " +
                                    cLibrary.lib.strerror(
                                        Native.getLastError()));
        }

        cLibrary.NetlinkSockAddress local = new cLibrary.NetlinkSockAddress();
        IntByReference localSize = new IntByReference(local.size());

        if (cLibrary.lib.getsockname(fdVal, local, localSize) < 0) {
            throw
                new IOException("failed to connect to socket: " +
                                    cLibrary.lib.strerror(
                                        Native.getLastError()));
        }

        log.debug("Netlink connection returned pid: {}.",
                  local.nl_pid);
        localAddress = new Netlink.Address(local.nl_pid);

        if (cLibrary.lib.bind(fdVal, local, local.size()) < 0) {
            throw
                new IOException("failed to connect to socket: " +
                                    cLibrary.lib.strerror(
                                        Native.getLastError()));
        }

        state = ST_CONNECTED;
    }
}
