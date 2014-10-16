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
