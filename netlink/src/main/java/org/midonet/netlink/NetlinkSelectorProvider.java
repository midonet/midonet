/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.netlink;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.ProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Pipe;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// uncomment this and the commented method below to let it compile for jdk7 properly.
// import java.net.ProtocolFamily

/**
 * A {@link SelectorProvider} service implementation that can create
 * NetlinkChannel and UnixDomainChannel objects.
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


    /**
     * WARN: implementation for jdk7 SelectorProvider methods
     */
    @Override
    public DatagramChannel openDatagramChannel(ProtocolFamily family)
        throws IOException {
        return underlyingSelector.openDatagramChannel(family);
    }

    public NetlinkChannel openNetlinkSocketChannel(Netlink.Protocol protocol) {
        final String NAME = "org.midonet.netlink.NetlinkChannelImpl";
        //final String NAME = "org.midonet.netlink.NetlinkTracingChannelImpl";

        try {
            Class<? extends NetlinkChannel> clazz =
                (Class<? extends NetlinkChannel>) Class.forName(NAME);

            Constructor<? extends NetlinkChannel> constructor =
                clazz.getConstructor(SelectorProvider.class, Netlink.Protocol.class);

            return constructor.newInstance(this, protocol);
        } catch (Throwable e) {
            log.error("Exception while instantiating class of type: {}", NAME, e);
            return null;
        }
    }

    public UnixDomainChannel openUnixDomainSocketChannel(AfUnix.Type socketType) {
        final String NAME = "org.midonet.netlink.UnixDomainChannelImpl";
        //final String NAME = "org.midonet.netlink.NetlinkTracingChannelImpl";

        try {
            Class<? extends UnixDomainChannel> clazz =
                    (Class<? extends UnixDomainChannel>) Class.forName(NAME);

            Constructor<? extends UnixDomainChannel> constructor =
                    clazz.getConstructor(SelectorProvider.class, AfUnix.Type.class);

            return constructor.newInstance(this, socketType);
        } catch (Throwable e) {
            log.error("Exception while instantiating class of type: {}", NAME, e);
            return null;
        }
    }
}
