/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.midolman.state;

import java.util.UUID;

import org.midonet.packets.IPv4Addr;

/**
 * A fixed size block of ports associated with a particular IP address under a
 * virtual device.
 */
public final class NatBlock extends NatRange {
    public static final int BLOCK_SIZE = 64; // Must be power of 2
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
