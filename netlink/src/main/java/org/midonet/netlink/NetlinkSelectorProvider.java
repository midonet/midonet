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

    public NetlinkChannel openNetlinkSocketChannel(NetlinkProtocol prot) {
        String type = "org.midonet.netlink.NetlinkChannelImpl";
        Class[] argTypes = {SelectorProvider.class, NetlinkProtocol.class};
        Object[] args = {this, prot};

        return (NetlinkChannel) makeInstanceOf(type, argTypes, args);
    }

    public NetlinkChannel openMockNetlinkSocketChannel(NetlinkProtocol prot) {
        String type = "org.midonet.netlink.MockNetlinkChannel";
        Class[] argTypes = {SelectorProvider.class, NetlinkProtocol.class};
        Object[] args = {this, prot};

        return (NetlinkChannel) makeInstanceOf(type, argTypes, args);
    }

    public UnixDomainChannel openUnixDomainSocketChannel(AfUnix.Type socketType) {
        String type = "org.midonet.netlink.UnixDomainChannelImpl";
        Class[] argTypes = {SelectorProvider.class, AfUnix.Type.class};
        Object[] args = {this, socketType};

        return (UnixDomainChannel) makeInstanceOf(type, argTypes, args);
    }

    private Object makeInstanceOf(
            String type, Class[] argTypes, Object[] args) {
        try {
            Class<?> clazz = Class.forName(type);
            Constructor<?> constructor = clazz.getConstructor(argTypes);
            return constructor.newInstance(args);
        } catch (ClassNotFoundException e) {
            log.error("Can't find class of type: {}", type);
        } catch (SecurityException e) {
            log.error("Security exception when trying to instantiate class {}"
                + "   midonet-jdk-boostrap might be missing ? {}", type, e);
        } catch (Throwable e) {
            log.error("Exception making instance of class {}: {}", type, e);
        }
        return null;
    }

}
