/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.netlink;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.nio.ch.SelChImpl;
import sun.nio.ch.SelectionKeyImpl;

import com.midokura.netlink.clib.cLibrary;
import com.midokura.netlink.hacks.IOUtil;
import com.midokura.netlink.hacks.PollArrayWrapper;
import com.midokura.netlink.hacks.SelectionKeyImplCaller;
import com.midokura.netlink.hacks.SelectorCaller;

/**
 * Implementation of a NetlinkChannel.
 */
public class NetlinkChannelImpl extends NetlinkChannel implements SelChImpl {

    private static final Logger log = LoggerFactory
        .getLogger(NetlinkChannelImpl.class);

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

    public boolean translateAndUpdateReadyOps(int ops, SelectionKeyImpl sk) {
        return translateReadyOps(ops, SelectionKeyImplCaller.nioReadyOps(sk), sk);
    }

    public boolean translateAndSetReadyOps(int ops, SelectionKeyImpl sk) {
        return translateReadyOps(ops, 0, sk);
    }

    public void translateAndSetInterestOps(int ops, SelectionKeyImpl sk) {
        int newOps = 0;

        if ((ops & SelectionKey.OP_READ) != 0)
            newOps |= PollArrayWrapper.POLLIN;
        if ((ops & SelectionKey.OP_WRITE) != 0)
            newOps |= PollArrayWrapper.POLLOUT;
        if ((ops & SelectionKey.OP_CONNECT) != 0)
            newOps |= PollArrayWrapper.POLLIN;

        SelectorCaller.putEventOps(sk.selector(), sk, newOps);
    }

    @Override
    public void kill() throws IOException {
        _kill();
    }

    @Override
    public FileDescriptor getFD() {
        return fd;
    }

    @Override
    public int getFDVal() {
        return fdVal;
    }
}
