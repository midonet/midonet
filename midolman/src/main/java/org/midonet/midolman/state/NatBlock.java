/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state;

import java.util.UUID;

import org.midonet.packets.IPv4Addr;

/**
 * A fixed size block of ports associated with a particular IP address under a
 * virtual device.
 */
public final class NatBlock extends NatRange {
    public static final int BLOCK_SIZE = 64;
    public static final int TOTAL_BLOCKS = 1024;

    public static final NatBlock NO_BLOCK =
            new NatBlock(UUID.randomUUID(), IPv4Addr.random(), 0);

    public final int blockIndex;

    public NatBlock(UUID deviceId, IPv4Addr ip, int blockIndex) {
        super(deviceId, ip, blockIndex * BLOCK_SIZE,
              blockIndex * BLOCK_SIZE + BLOCK_SIZE - 1);
        this.blockIndex = blockIndex;
    }

    @Override
    public String toString() {
        return "NatBlock[deviceId=" + deviceId + "; ip=" + ip + "; tpPortStart=" +
                tpPortStart + "; tbPortEnd=" + tpPortEnd + "]";
    }
}
