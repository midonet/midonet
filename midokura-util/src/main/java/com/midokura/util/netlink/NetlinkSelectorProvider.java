/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink;

import java.io.IOException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Pipe;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * // TODO: Explain yourself.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 6/27/12
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

    public NetlinkChannel openNetlinkSocketChannel(
        Netlink.Protocol protocol) {
        try {
            final Class y = Class.forName("sun.nio.ch.SelChImpl");
            final Class z = Class.forName(
                "com.midokura.util.netlink.NetlinkChannel");

            ClassLoader x = new ClassLoader(getClass().getClassLoader()) {
                @Override
                public Class<?> loadClass(String name)
                    throws ClassNotFoundException {
                    if (name.equals("sun.nio.ch.SelChImpl")) {
                        return y;
                    }

                    if (name.equals(
                        "com.midokura.util.netlink.NetlinkChannel")) {
                        return z;
                    }

                    return super.loadClass(name);
                }

                @Override
                protected synchronized Class<?> loadClass(String name,
                                                          boolean resolve)
                    throws ClassNotFoundException {
                    return super.loadClass(name,
                                           resolve);    //To change body of overridden methods use File | Settings | File Templates.
                }

                @Override
                protected Class<?> findClass(String name)
                    throws ClassNotFoundException {

                    if (name.equals("sun.nio.ch.SelChImpl")) {
                        return y;
                    }

                    if (name.equals(
                        "com.midokura.util.netlink.NetlinkChannel")) {
                        return z;
                    }

                    return super.findClass(name);
                }
            };

            final NetlinkChannelImpl channel = new NetlinkChannelImpl(this,
                                                                          protocol);
//            final NetlinkChannelImpl2 impl =
//                (NetlinkChannelImpl2) Class.forName(
//                    "com.midokura.util.netlink.NetlinkChannelImpl")
//                                          .getConstructor(
//                                              SelectorProvider.class, Netlink.Protocol.class)
//                                          .newInstance(this,
//                                                       protocol);
//            Class.forName("com.midokura.util.netlink.NetlinkChannel");

//            Object proxy = Proxy.newProxyInstance(
//                ClassLoader.getSystemClassLoader(),
//                new Class[]{NetlinkChImpl.class},
//                new InvocationHandler() {
//                    @Override
//                    public Object invoke(Object proxy, Method method,
//                                         Object[] args)
//                        throws Throwable {
//                        try {
//                            return method.invoke(channel, args);
//                        } catch (InvocationTargetException e) {
//                            throw e.getTargetException();
//                        }
//                    }
//                });
//
//            return (NetlinkChannel) proxy;
            return channel;
        } catch (ClassNotFoundException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }

        return null;
//        return new NetlinkChannelImpl(this, protocol);
    }
}
