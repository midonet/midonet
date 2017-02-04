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

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.ActorSystem
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.backend.cassandra.CassandraClient
import org.midonet.cluster.storage.{FlowStateStorageImpl, FlowStateStorage}
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.state.ConnTrackState.ConnTrackKey
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.packets.ConnTrackState.ConnTrackKeyStore
import org.midonet.packets.IPv4Addr
import org.midonet.packets.NatState.{NatKeyStore, NatBinding, FWD_SNAT}
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class FlowStateStorageTest extends FeatureSpec
                            with BeforeAndAfter
                            with Matchers
                            with OneInstancePerTest
                            with GivenWhenThen
                            with CuratorTestFramework
                            with MidonetEventually {

    implicit def stringToIp(str: String): IPv4Addr = IPv4Addr.fromString(str)

    implicit val actors = ActorSystem.create()
    import actors.dispatcher

    val connTrackKeys =
        List(ConnTrackKey("10.0.0.1", 1234, "10.0.0.2", 22, 1, UUID.randomUUID()),
            ConnTrackKey("10.0.0.9", 4578, "10.0.0.12", 80, 2, UUID.randomUUID()))

    val natMappings = Map(
        NatKey(FWD_SNAT, "192.168.10.1", 10001, "17.16.15.1", 80, 1, UUID.randomUUID()) ->
               NatBinding("1.2.3.4", 54321),
        NatKey(FWD_SNAT, "192.168.10.2", 10002, "17.16.15.2", 443, 2, UUID.randomUUID()) ->
               NatBinding("4.3.2.1", 12345))

    val ingressPort = UUID.randomUUID()
    val egressPorts = List(UUID.randomUUID(), UUID.randomUUID())

    var cass: CassandraClient = _
    var storage: FlowStateStorage[ConnTrackKey, NatKey] = _

    before {
        val confValues = s"""
          |zookeeper.zookeeper_hosts = "${zk.getConnectString}"
          |cassandra.servers = "127.0.0.1:9142"
        """.stripMargin
        val config = MidolmanConfig.forTests(confValues)

        cass = new CassandraClient(config.zookeeper, config.cassandra,
                                   "MidonetFlowState",
                                   FlowStateStorage.SCHEMA,
                                   FlowStateStorage.SCHEMA_TABLE_NAMES)
        val sessionF = cass.connect()
        storage = FlowStateStorage[ConnTrackKey, NatKey](
            Await.result(sessionF, 60 seconds), NatKey, ConnTrackKey)
        eventually {
            storage.asInstanceOf[
                FlowStateStorageImpl[
                    ConnTrackKeyStore, NatKeyStore]].session should not be null
        }
    }

    feature("Stores and fetches state from cassandra") {
        scenario("Conntrack keys and NAT bindings") {
            for (k <- connTrackKeys) {
                storage.touchConnTrackKey(k, ingressPort, egressPorts.asJava.iterator())
            }
            for ((k,v) <- natMappings) {
                storage.touchNatKey(k, v, ingressPort, egressPorts.asJava.iterator())
            }
            storage.submit()

            var strongConn: java.util.Set[ConnTrackKey] = null
            eventually {
                strongConn = storage.fetchStrongConnTrackRefs(ingressPort).await()
                strongConn should not be null
                strongConn should have size connTrackKeys.size
            }

            for (k <- connTrackKeys) {
                strongConn should contain (k)
            }

            for (port <- egressPorts) {
                val weakRefs = storage.fetchWeakConnTrackRefs(port).await()
                for (k <- connTrackKeys) {
                    weakRefs should contain (k)
                }
            }

            val strongNat = storage.fetchStrongNatRefs(ingressPort).await()
            for ((k, v) <- natMappings) {
                strongNat.get(k) should === (v)
            }

            for (port <- egressPorts) {
                val weakRefs = storage.fetchWeakNatRefs(port).await()
                for ((k, v) <- natMappings) {
                    weakRefs.get(k) should === (v)
                }
            }
        }
    }
}
