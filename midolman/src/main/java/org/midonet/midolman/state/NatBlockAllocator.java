/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state;

import org.midonet.util.functors.Callback;

/**
 * Allocated NAT blocks consisting of an IP address and a fixed-size range of
 * 64 contiguous ports.
 */
public interface NatBlockAllocator {
    /**
     * Asynchronously allocates a block from the specified range.
     *
     * @param natBlock The NatRange specifying the allowed range (of any size).
     * @param callback Used to return the NatBlock identifying the assigned 64
     *                 port range or NatBlock.NO_BLOCK if none was found.
     *
     * TODO: Use java.util.Optional<NatBlock> when we move to Java8
     */
    void allocateBlockInRange(NatRange natRange,
                              Callback<NatBlock, Exception> callback);

    /**
     * Asynchronously frees the specified block.
     */
    void freeBlock(NatBlock natBlock);
}
