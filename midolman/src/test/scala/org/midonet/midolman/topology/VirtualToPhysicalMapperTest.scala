/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.midolman.topology

import java.util.UUID
import scala.util.Random

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.testkit.ImplicitSender
import akka.testkit.TestActorRef
import akka.testkit.TestKit
import scala.concurrent.duration._
import org.apache.zookeeper.KeeperException
import org.junit.runner.RunWith
import org.scalatest.{OneInstancePerTest, BeforeAndAfter, Matchers, Suite}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.TunnelZone
import org.midonet.cluster.data.zones.GreTunnelZoneHost
import org.midonet.cluster.data.zones.IpsecTunnelZoneHost
import org.midonet.midolman.topology.rcu.Host

object TestVTPMActor {

    case class ZkNotifyError(err: Option[KeeperException], id: UUID, retry: Runnable)
    case class NotifyLocalPortActive(vportID: UUID, active: Boolean)
    case class SubscribePortSet(psetID: UUID)
    case class UnsubscribePortSet(psetID: UUID)
    case class HandleMsg(id: UUID)

    trait MockVTPMExtensions {
        val listener : ActorRef
        val handler : DeviceHandler
        def notifyLocalPortActive(vportID: UUID, active: Boolean) {
            listener ! NotifyLocalPortActive(vportID, active)
        }
        def subscribePortSet(id: UUID) { listener ! SubscribePortSet(id) }
        def unsubscribePortSet(id: UUID) { listener ! UnsubscribePortSet(id) }
        def notifyError(err: Option[KeeperException], id: UUID, retry: Runnable) {
            listener ! ZkNotifyError(err, id, retry)
        }
        def makeHostManager(actor: ActorRef) = handler
        def makePortSetManager(actor: ActorRef) = handler
        def makeTunnelZoneManager(actor: ActorRef) = handler
    }

    def apply(actor: ActorRef, devhandler: DeviceHandler) =
        new VirtualToPhysicalMapperBase with MockVTPMExtensions {
            val listener = actor
            val handler = devhandler
        }

}

@RunWith(classOf[JUnitRunner])
class VirtualToPhysicalMapperTest
        extends TestKit(ActorSystem("VirtualToPhysicalMapperTest"))
        with ImplicitSender with Suite with Matchers with BeforeAndAfter
        with OneInstancePerTest with DeviceHandler {

    import TestVTPMActor._
    import VirtualToPhysicalMapper._

    val r = new Random

    val id = UUID.randomUUID()

    def handle(deviceId: UUID) { self ! HandleMsg(deviceId) }

    def getVTPM() = TestActorRef({ TestVTPMActor(self, this) }, "TestVTPMActor")

    // the testkit actor is reused between tests, therefore we need to drain
    // its mailbox between tests in case some tests fail to receive all msgs.
    before { while (msgAvailable) receiveOne(0 seconds) }

    def assertNoMsg { msgAvailable should not be (true) }

    def testSmoke() {
        val vtpm = getVTPM()
        vtpm.underlyingActor.notifyLocalPortActive(id, true)
        expectMsg(NotifyLocalPortActive(id, true))
        assertNoMsg
    }

    def testHostRequest() {

        def getPortMap(max_size : Int = 2): Map[UUID,String] =
            List.tabulate(r.nextInt(max_size)) { _ => UUID.randomUUID }
                .foldLeft(Map[UUID,String]()) { (m,id) => m + (id -> "foo")}

        def getTZMap = Map[UUID,TunnelZone.HostConfig[_,_]]()

        def getHost(id: UUID) = Host(id, "midonet", getPortMap(), getTZMap)

        val hosts = List.tabulate(5) { _ => getHost(id) }

        val vtpm = getVTPM()

        vtpm ! HostRequest(id)
        expectMsg(HandleMsg(id))

        for (h <- hosts) { vtpm ! h }
        for (h <- hosts) { expectMsg(h) }
        assertNoMsg
    }

    def testTZRequest() {

        def getGreTZHost = new GreTunnelZoneHost(UUID.randomUUID)

        val tzhost1 = getGreTZHost
        val tzhost2 = getGreTZHost
        val tzhost3 = getGreTZHost

        val vtpm = getVTPM()

        vtpm ! TunnelZoneRequest(id)
        expectMsg(HandleMsg(id))

        val tzEvent1 = GreZoneChanged(id, tzhost1, HostConfigOperation.Added)
        vtpm ! tzEvent1
        expectMsg(GreZoneMembers(id, Set(tzhost1)))

        val tzEvent2 = GreZoneChanged(id, tzhost2, HostConfigOperation.Added)
        vtpm ! tzEvent2
        expectMsg(tzEvent2)

        vtpm ! TunnelZoneUnsubscribe(id)

        val tzEvent3 = GreZoneChanged(id, tzhost3, HostConfigOperation.Added)
        vtpm ! tzEvent3
        assertNoMsg

        vtpm ! TunnelZoneRequest(id)
        expectMsg(GreZoneMembers(id, Set(tzhost1, tzhost2, tzhost3)))

        val tzEvent4 = GreZoneChanged(id, tzhost1, HostConfigOperation.Deleted)
        vtpm ! tzEvent4
        expectMsg(tzEvent4)

        vtpm ! TunnelZoneUnsubscribe(id)

        val tzEvent5 = GreZoneChanged(id, tzhost3, HostConfigOperation.Deleted)
        vtpm ! tzEvent5

        val tzEvent6 = GreZoneChanged(id, tzhost2, HostConfigOperation.Added)
        vtpm ! tzEvent6

        vtpm ! TunnelZoneRequest(id)
        expectMsg(GreZoneMembers(id, Set(tzhost2)))
    }
}
