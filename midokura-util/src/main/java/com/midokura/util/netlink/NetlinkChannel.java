/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink;

import java.io.IOException;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.SelectorProvider;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 6/28/12
 */
public abstract class NetlinkChannel extends AbstractSelectableChannel
    implements ByteChannel, ScatteringByteChannel,
               GatheringByteChannel, Channel,
               InterruptibleChannel
{
    protected NetlinkChannel(SelectorProvider provider) {
        super(provider);
    }

    public abstract boolean connect(Netlink.Address address) throws IOException;

    public abstract Netlink.Address getRemoteAddress();

    public abstract Netlink.Address getLocalAddress();
}
