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

package org.midonet.cluster.util

import scala.util.Failure

import com.typesafe.config.ConfigFactory
import org.apache.curator.RetryPolicy
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.RetryNTimes
import org.apache.zookeeper.KeeperException
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{GivenWhenThen, Matchers, FeatureSpecLike}
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.MidoSequenceType.AGENT_TUNNEL_KEYS
import org.midonet.util.concurrent.toFutureOps
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class MidoSequencersTest extends FeatureSpecLike
                         with Matchers
                         with GivenWhenThen
                         with CuratorTestFramework {

    private val timeout = 3.seconds

    val illCurator = CuratorFrameworkFactory.newClient("localhost:2888",
                                                       1000,
                                                       1000,
                                                       new RetryNTimes(2, 500))
    illCurator.start() // will fail, but we need it started so it takes reqs.

    private val conf = new MidonetBackendConfig(ConfigFactory.parseString(
        s"""
          |zookeeper {
          |    use_new_stack = false
          |    curator_enabled = false
          |    root_key = $ZK_ROOT
          |}
        """.stripMargin))

    feature("The sequencer sequences in the happy case") {
        scenario("The ZK connection is up and running") {
            val sequencer = new MidoSequencers(curator, conf)
            MidoSequenceType.values.foreach { t =>
                sequencer.current(t).await(timeout) shouldBe 0
                sequencer.next(t).await(timeout).get shouldBe 1
                sequencer.next(t).await(timeout).get shouldBe 2

                // Make sure we're storing things in the right place
                curator.checkExists().forPath(ZK_ROOT + "/" + t)
            }
        }
    }

    feature("The connection is broken") {
        scenario("Get for supported sequences fails") {
            intercept[KeeperException.ConnectionLossException] {
                val sequencer = new MidoSequencers(illCurator, conf)
                sequencer.current(AGENT_TUNNEL_KEYS).await(timeout)
            }
        }
        scenario("Next for supported sequences fails") {
            intercept[KeeperException.ConnectionLossException] {
                val sequencer = new MidoSequencers(illCurator, conf)
                sequencer.next(AGENT_TUNNEL_KEYS).await(timeout)
            }
        }
    }

}
