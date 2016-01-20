/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.midolman.state

import scala.concurrent.Future

object NatBlockAllocator {
    object NoFreeNatBlocksException extends Exception {
        override def fillInStackTrace(): Throwable = this
    }
}

/**
 * Allocated NAT blocks consisting of an IP address and a fixed-size range of
 * 64 contiguous ports.
 */
trait NatBlockAllocator {

    /**
     * Asynchronously allocates a block from the specified range.
     *
     * @param natRange The NatRange specifying the allowed range (of any size).
     * @return         The future completed with the NatBlock identifying the
     *                 assigned 64 port range or failed with the
     *                 NoFreeNatBlocksException.
     */
    def allocateBlockInRange(natRange: NatRange): Future[NatBlock]

    /**
     * Asynchronously frees the specified block.
     */
    def freeBlock(natBlock: NatBlock): Unit
}
