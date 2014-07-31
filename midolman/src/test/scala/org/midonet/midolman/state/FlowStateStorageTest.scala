/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state

import java.util.UUID
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.ActorSystem
import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually._
import org.scalatest.junit.JUnitRunner
import org.scalatest._

import org.midonet.midolman.state.ConnTrackState.ConnTrackKey
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.packets.{IPAddr, IPv4Addr}
import org.midonet.cassandra.CassandraClient


@RunWith(classOf[JUnitRunner])
class FlowStateStorageTest extends FeatureSpec
                            with BeforeAndAfter
                            with ShouldMatchers
                            with OneInstancePerTest
                            with GivenWhenThen {
    implicit def stringToIp(str: String): IPAddr = IPv4Addr.fromString(str)

    implicit val actors = ActorSystem.create()
    import actors.dispatcher

    val timeout: Duration = 3 seconds

    val connTrackKeys =
        List(ConnTrackKey("10.0.0.1", 1234, "10.0.0.2", 22, 1, UUID.randomUUID()),
            ConnTrackKey("10.0.0.9", 4578, "10.0.0.12", 80, 2, UUID.randomUUID()))

    val natMappings = Map(
        NatKey(NatKey.FWD_SNAT, "192.168.10.1", 10001, "17.16.15.1", 80, 1, UUID.randomUUID()) ->
               NatBinding("1.2.3.4", 54321),
        NatKey(NatKey.FWD_SNAT, "192.168.10.2", 10002, "17.16.15.2", 443, 2, UUID.randomUUID()) ->
               NatBinding("4.3.2.1", 12345))

    val ingressPort = UUID.randomUUID()
    val egressPorts = List(UUID.randomUUID(), UUID.randomUUID())

    var cass: CassandraClient = _
    var storage: FlowStateStorage = _

    before {
        EmbeddedCassandraServerHelper.startEmbeddedCassandra()
        Thread.sleep(10000L)
        cass = new CassandraClient("127.0.0.1:9142", "TestCluster",
                                   "MidonetFlowState", 1,
                                   FlowStateStorage.SCHEMA, null)
        cass.connect()
        storage = FlowStateStorage(cass)
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
                val future = storage.fetchStrongConnTrackRefs(ingressPort)
                strongConn = Await.result(future, timeout)
                strongConn should not be null
                strongConn should not be empty
            }

            for (k <- connTrackKeys) {
                strongConn should contain (k)
            }

            for (port <- egressPorts) {
                val weakRefs = Await.result(storage.fetchWeakConnTrackRefs(port), timeout)
                for (k <- connTrackKeys) {
                    weakRefs should contain (k)
                }
            }

            val strongNat = Await.result(storage.fetchStrongNatRefs(ingressPort), timeout)
            for ((k, v) <- natMappings) {
                strongNat.get(k) should === (v)
            }

            for (port <- egressPorts) {
                val weakRefs = Await.result(storage.fetchWeakNatRefs(port), timeout)
                for ((k, v) <- natMappings) {
                    weakRefs.get(k) should === (v)
                }
            }
        }
    }
}
