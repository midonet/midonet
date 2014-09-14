/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state

import org.midonet.util.functors.Callback

class MockNatBlockAllocator extends NatBlockAllocator {

    override def freeBlock(natBlock: NatBlock): Unit = {}

    override def allocateBlockInRange(natRange: NatRange,
                                      callback: Callback[NatBlock, Exception]): Unit =
        callback.onSuccess(new NatBlock(
            natRange.deviceId,
            natRange.ip,
            natRange.tpPortStart / NatBlock.BLOCK_SIZE))
}
