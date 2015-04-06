/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.netlink;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.clib.cLibrary;
import org.midonet.netlink.hacks.IOUtil;

/**
 * Channel wrapping a Netlink socket connection on the local machine.
 */
public class NetlinkChannel extends UnixChannel<Netlink.Address> {

    private static final Logger log =
        LoggerFactory.getLogger("org.midonet.netlink.channel");

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

    private Selector selector = null;

    protected final NetlinkProtocol protocol;

    protected final int groups;

    protected NetlinkChannel(SelectorProvider provider,
                             NetlinkProtocol protocol) {
        this(provider, protocol, 0);
    }

    protected NetlinkChannel(SelectorProvider provider,
                             NetlinkProtocol protocol,
                             int groups) {
        super(provider);
        this.protocol = protocol;
        this.groups = groups;
        this.state = ST_UNCONNECTED;
        initSocket();
    }

    public Selector selector() throws IOException {
        if (selector == null) {
            selector = provider().openSelector();
        }
        return selector;
    }

    protected void initSocket() {
        int socket = cLibrary.lib.socket(cLibrary.AF_NETLINK, cLibrary.SOCK_RAW,
                                         protocol.value());

        if (socket == -1) {
            log.error("Could not create netlink socket: {}",
                      cLibrary.lib.strerror(Native.getLastError()));
        }

        fd = IOUtil.newFD(socket);
        fdVal = IOUtil.fdVal(fd);

        ByteBuffer sobuf = BytesUtil.instance.allocate(4);
        sobuf.putInt(RCVBUF_SIZE);

        int sockoptret = cLibrary.lib.setsockopt(
            fdVal, cLibrary.SOL_SOCKET, cLibrary.SO_RCVBUFFORCE, sobuf, 4);
        if (sockoptret != 0) {
            log.error("SETSOCKOPT failed: {}",
                cLibrary.lib.strerror(Native.getLastError()));
        } else {
            log.debug("Successfully set netlink channel RCVBUFF size to {}",
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
        ByteBuffer sobuf2 = BytesUtil.instance.allocate(4);
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
            throw new IOException("failed to connect to socket: " +
                    cLibrary.lib.strerror(Native.getLastError()));
        }

        log.debug("Netlink connection returned pid: {}.",
                  local.nl_pid);
        localAddress = new Netlink.Address(local.nl_pid);

        if (this.groups != 0)
            local.nl_groups = this.groups;

        if (cLibrary.lib.bind(fdVal, local, local.size()) < 0) {
            throw new IOException("failed to connect to socket: " +
                    cLibrary.lib.strerror(Native.getLastError()));
        }

        state = ST_CONNECTED;
    }
}
