/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.netlink;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Pipe;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link SelectorProvider} service implementation that can create
 * NetlinkChannel objects.
 */
public class NetlinkSelectorProvider extends SelectorProvider {

    private static final Logger log = LoggerFactory
        .getLogger(NetlinkSelectorProvider.class);
    public static final String SUN_DEFAULT_SELECTOR = "sun.nio.ch.DefaultSelectorProvider";

    SelectorProvider underlyingSelector;

    public NetlinkSelectorProvider() {
        try {
            underlyingSelector =
                (SelectorProvider) Class.forName(SUN_DEFAULT_SELECTOR)
                                        .getMethod("create")
                                        .invoke(null);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public DatagramChannel openDatagramChannel() throws IOException {
        return underlyingSelector.openDatagramChannel();
    }

    @Override
    public Pipe openPipe() throws IOException {
        return underlyingSelector.openPipe();
    }

    @Override
    public AbstractSelector openSelector() throws IOException {
        return underlyingSelector.openSelector();
    }

    @Override
    public ServerSocketChannel openServerSocketChannel() throws IOException {
        return underlyingSelector.openServerSocketChannel();
    }

    @Override
    public SocketChannel openSocketChannel() throws IOException {
        return underlyingSelector.openSocketChannel();
    }

    public NetlinkChannel openNetlinkSocketChannel(Netlink.Protocol protocol) {
        final String NAME = "com.midokura.netlink.NetlinkChannelImpl";
        //        final String NAME = "com.midokura.netlink.NetlinkTracingChannelImpl";

        try {
            Class<? extends NetlinkChannel> clazz =
                (Class<? extends NetlinkChannel>) Class.forName(NAME);

            Constructor<? extends NetlinkChannel> constructor =
                clazz.getConstructor(SelectorProvider.class, Netlink.Protocol.class);

            return constructor.newInstance(this, protocol);
        } catch (Exception e) {
            log.error("Exception while instantiating class of type: {}", NAME, e);
            return null;
        }
    }
}
