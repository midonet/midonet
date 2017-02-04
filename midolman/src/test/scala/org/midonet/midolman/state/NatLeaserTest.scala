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

package org.midonet.midolman.state

import java.util.UUID

import org.midonet.midolman.state.NatBlockAllocator.NoFreeNatBlocksException
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._

import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.slf4j.helpers.NOPLogger
import org.junit.runner.RunWith

import org.midonet.midolman.NotYetException
import org.midonet.midolman.rules.NatTarget
import org.midonet.midolman.state.NatLeaser.{NoNatBindingException, blockOf}
import org.midonet.packets.IPv4Addr
import org.midonet.util.concurrent.MockClock
import org.midonet.util.logging.Logger

@RunWith(classOf[JUnitRunner])
class NatLeaserTest extends FeatureSpec
                            with Matchers
                            with OneInstancePerTest {

    val dev = UUID.randomUUID()
    val clock = new MockClock()
    val allocatedBlocks = mutable.Set[NatBlock]()
    val natLeaser = new NatLeaser {
        override val log = Logger(NOPLogger.NOP_LOGGER)
        override val allocator: NatBlockAllocator = new NatBlockAllocator {
            override def allocateBlockInRange(natRange: NatRange) =
                (natRange.tpPortStart to natRange.tpPortEnd) map { port =>
                    new NatBlock(natRange.deviceId, natRange.ip, blockOf(port))
                } find { block =>
                    if (allocatedBlocks contains block) {
                        false
                    } else {
                        allocatedBlocks += block
                        true
                    }
                } map Future.successful getOrElse Future.failed(NoFreeNatBlocksException)

            override def freeBlock(natBlock: NatBlock): Unit = {}
        }

        override val clock = NatLeaserTest.this.clock
    }

    feature("NatBindings are allocated") {
        scenario("Simple case") {
            val natTarget = new NatTarget(IPv4Addr("10.0.1.1").addr,
                                          IPv4Addr("10.0.1.1").addr,
                                          11000,
                                          30000)
            intercept[NotYetException] {
                natLeaser.allocateNatBinding(dev, IPv4Addr.random, 10, Array(natTarget))
            }

            val binding = natLeaser.allocateNatBinding(dev, IPv4Addr.random, 10, Array(natTarget))

            binding.networkAddress should be (natTarget.nwStart)
            binding.transportPort should be >= natTarget.tpStart
            binding.transportPort should be <= natTarget.tpEnd
        }

        scenario("Oversubscription") {
            val natTarget = new NatTarget(IPv4Addr("10.0.1.1").addr,
                                          IPv4Addr("10.0.1.1").addr,
                                          11000,
                                          11000)
            val ip1 = IPv4Addr.random
            val ip2 = ip1.next

            intercept[NotYetException] {
                natLeaser.allocateNatBinding(dev, ip1, 10, Array(natTarget))
            }

            val binding = natLeaser.allocateNatBinding(dev, ip1, 10, Array(natTarget))
            val nextBinding = natLeaser.allocateNatBinding(dev, ip2, 10, Array(natTarget))
            val otherBinding = natLeaser.allocateNatBinding(dev, ip2, 11, Array(natTarget))

            binding.networkAddress should be (nextBinding.networkAddress)
            binding.transportPort should be (nextBinding.transportPort)
            binding.networkAddress should be (otherBinding.networkAddress)
            binding.transportPort should be (otherBinding.transportPort)
        }

        scenario("Collision") {
            val natTarget = new NatTarget(IPv4Addr("10.0.1.1").addr,
                                          IPv4Addr("10.0.1.1").addr,
                                          11000,
                                          11000)
            val ip = IPv4Addr.random

            intercept[NotYetException] {
                 natLeaser.allocateNatBinding(dev, ip, 10, Array(natTarget))
            }

            val binding = natLeaser.allocateNatBinding(dev, ip, 10, Array(natTarget))
            binding.networkAddress should be (natTarget.nwStart)
            binding.transportPort should be (natTarget.tpStart)

            val f = intercept[NotYetException] {
                natLeaser.allocateNatBinding(dev, ip, 10, Array(natTarget))
            }

            trap { f.waitFor.value.get.get } should be (NoNatBindingException)
        }

        scenario("Round robin allocation") {
            val natTarget = new NatTarget(IPv4Addr("10.0.1.1").addr,
                                         IPv4Addr("10.0.1.1").addr,
                                         11200,
                                         30000)
            intercept[NotYetException] {
                natLeaser.allocateNatBinding(dev, IPv4Addr.random, 10, Array(natTarget))
            }

            val binding = natLeaser.allocateNatBinding(dev, IPv4Addr.random, 10, Array(natTarget))
            val nextBinding = natLeaser.allocateNatBinding(dev, IPv4Addr.random, 10, Array(natTarget))

            binding.networkAddress should be (natTarget.nwStart)
            binding.transportPort should be >= natTarget.tpStart
            binding.transportPort should be <= natTarget.tpEnd

            nextBinding.networkAddress should be (binding.networkAddress)
            nextBinding.transportPort should not be binding.transportPort
        }

        scenario("Multiple blocks same IP") {
            val natTarget = new NatTarget(IPv4Addr("10.0.1.1").addr,
                                          IPv4Addr("10.0.1.1").addr,
                                          NatBlock.BLOCK_SIZE - 1,
                                          NatBlock.BLOCK_SIZE)
            val ip = IPv4Addr.random
            val port = 0
            intercept[NotYetException] {
                natLeaser.allocateNatBinding(dev, ip, port, Array(natTarget))
            }

            val binding = natLeaser.allocateNatBinding(dev, ip, port, Array(natTarget))

            intercept[NotYetException] {
                natLeaser.allocateNatBinding(dev, ip, port, Array(natTarget))
            }

            val nextBinding = natLeaser.allocateNatBinding(dev, ip, port, Array(natTarget))

            binding.networkAddress should be (IPv4Addr("10.0.1.1"))
            binding.transportPort should be (NatBlock.BLOCK_SIZE - 1)

            nextBinding.networkAddress should be (IPv4Addr("10.0.1.1"))
            nextBinding.transportPort should be (NatBlock.BLOCK_SIZE)

            allocatedBlocks should be (mutable.Set(
                new NatBlock(dev, IPv4Addr("10.0.1.1"), 0),
                new NatBlock(dev, IPv4Addr("10.0.1.1"), 1)))
        }

        scenario("Multiple blocks different IP") {
            val natTarget = new NatTarget(IPv4Addr("10.0.1.1").addr,
                                          IPv4Addr("10.0.1.2").addr,
                                          1,
                                          1)
            val ip = IPv4Addr.random
            val port = 0
            intercept[NotYetException] {
                natLeaser.allocateNatBinding(dev, ip, port, Array(natTarget))
            }

            val binding = natLeaser.allocateNatBinding(dev, ip, port, Array(natTarget))

            intercept[NotYetException] {
                natLeaser.allocateNatBinding(dev, ip, port, Array(natTarget))
            }

            val nextBinding = natLeaser.allocateNatBinding(dev, ip, port, Array(natTarget))

            binding.networkAddress should be (IPv4Addr("10.0.1.1"))
            binding.transportPort should be (1)

            nextBinding.networkAddress should be (IPv4Addr("10.0.1.2"))
            nextBinding.transportPort should be (1)

            allocatedBlocks should be (mutable.Set(
                new NatBlock(dev, IPv4Addr("10.0.1.1"), 0),
                new NatBlock(dev, IPv4Addr("10.0.1.2"), 0)))
        }

        scenario("Multiple blocks different target") {
            val natTargets = Array(
                new NatTarget(IPv4Addr("10.0.1.1").addr, IPv4Addr("10.0.1.1").addr, 1, 1),
                new NatTarget(IPv4Addr("10.0.1.2").addr, IPv4Addr("10.0.1.2").addr, 1, 1))

            val ip = IPv4Addr.random
            val port = 0
            intercept[NotYetException] {
                natLeaser.allocateNatBinding(dev, ip, port, natTargets)
            }

            val binding = natLeaser.allocateNatBinding(dev, ip, port, natTargets)

            intercept[NotYetException] {
                natLeaser.allocateNatBinding(dev, ip, port, natTargets)
            }

            val nextBinding = natLeaser.allocateNatBinding(dev, ip, port, natTargets)

            binding.networkAddress should be (IPv4Addr("10.0.1.1"))
            binding.transportPort should be (1)

            nextBinding.networkAddress should be (IPv4Addr("10.0.1.2"))
            nextBinding.transportPort should be (1)

            allocatedBlocks should be (mutable.Set(
                new NatBlock(dev, IPv4Addr("10.0.1.1"), 0),
                new NatBlock(dev, IPv4Addr("10.0.1.2"), 0)))
        }
    }

    feature("NatBindings are released") {
        scenario("Simple case") {
            val natTarget = new NatTarget(IPv4Addr("10.0.1.1").addr,
                                          IPv4Addr("10.0.1.1").addr,
                                          11000,
                                          30000)
            val ip = IPv4Addr.random
            intercept[NotYetException] {
                natLeaser.allocateNatBinding(dev, ip, 10, Array(natTarget))
            }

            val binding = natLeaser.allocateNatBinding(dev, ip, 10, Array(natTarget))

            natLeaser.freeNatBinding(dev, ip, 10, binding)

            val recoveredBinding = natLeaser.allocateNatBinding(dev, ip, 10, Array(natTarget))
            clock.time += (NatLeaser.BLOCK_EXPIRATION + (10 seconds)).toNanos
            natLeaser.obliterateUnusedBlocks()

            natLeaser.freeNatBinding(dev, ip, 10, recoveredBinding)
            clock.time += (NatLeaser.BLOCK_EXPIRATION + (10 seconds)).toNanos
            natLeaser.obliterateUnusedBlocks()

            intercept[NotYetException] {
                natLeaser.allocateNatBinding(dev, ip, 10, Array(natTarget))
            }
        }

        scenario("Multiple references") {
            val natTarget = new NatTarget(IPv4Addr("10.0.1.1").addr,
                                          IPv4Addr("10.0.1.1").addr,
                                          1,
                                          2)
            val ip = IPv4Addr.random
            val port = 0
            intercept[NotYetException] {
                natLeaser.allocateNatBinding(dev, ip, port, Array(natTarget))
            }

            val binding = natLeaser.allocateNatBinding(dev, ip, port, Array(natTarget))
            val otherBinding = natLeaser.allocateNatBinding(dev, ip, port, Array(natTarget))

            binding.networkAddress should be (otherBinding.networkAddress)
            binding.transportPort should not be otherBinding.transportPort

            allocatedBlocks should be (mutable.Set(
                new NatBlock(dev, IPv4Addr("10.0.1.1"), 0)))

            natLeaser.freeNatBinding(dev, ip, port, binding)
            clock.time += (NatLeaser.BLOCK_EXPIRATION + (10 seconds)).toNanos
            natLeaser.obliterateUnusedBlocks()

            val recoveredBinding = natLeaser.allocateNatBinding(dev, ip, port, Array(natTarget))
            recoveredBinding should be (binding)

            natLeaser.freeNatBinding(dev, ip, port, recoveredBinding)
            natLeaser.freeNatBinding(dev, ip, port, otherBinding)
            clock.time += (NatLeaser.BLOCK_EXPIRATION + (10 seconds)).toNanos
            natLeaser.obliterateUnusedBlocks()

            intercept[NotYetException] {
                natLeaser.allocateNatBinding(dev, ip, port, Array(natTarget))
            }
        }

        scenario("Multiple oversubscribed references") {
            val natTarget = new NatTarget(IPv4Addr("10.0.1.1").addr,
                                          IPv4Addr("10.0.1.1").addr,
                                          1,
                                          1)
            val ip = IPv4Addr.random
            intercept[NotYetException] {
               natLeaser.allocateNatBinding(dev, ip, 0, Array(natTarget))
            }

            val binding = natLeaser.allocateNatBinding(dev, ip, 0, Array(natTarget))
            val otherBinding = natLeaser.allocateNatBinding(dev, ip, 1, Array(natTarget))

            binding.networkAddress should be (otherBinding.networkAddress)
            binding.transportPort should be (otherBinding.transportPort)

            allocatedBlocks should be (mutable.Set(
                new NatBlock(dev, IPv4Addr("10.0.1.1"), 0)))

            natLeaser.freeNatBinding(dev, ip, 0, binding)
            clock.time += (NatLeaser.BLOCK_EXPIRATION + (10 seconds)).toNanos
            natLeaser.obliterateUnusedBlocks()

            val recoveredBinding = natLeaser.allocateNatBinding(dev, ip, 0, Array(natTarget))
            recoveredBinding should be (binding)

            natLeaser.freeNatBinding(dev, ip, 0, recoveredBinding)
            natLeaser.freeNatBinding(dev, ip, 1, otherBinding)
            clock.time += (NatLeaser.BLOCK_EXPIRATION + (10 seconds)).toNanos
            natLeaser.obliterateUnusedBlocks()

            intercept[NotYetException] {
               natLeaser.allocateNatBinding(dev, ip, 0, Array(natTarget))
            }
        }
    }
}
