/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.midolman.topology

import java.util.UUID

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.testkit.ImplicitSender
import akka.testkit.TestActorRef
import akka.testkit.TestKit
import akka.util.duration._
import org.apache.zookeeper.KeeperException
import org.junit.Ignore
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.Suite
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

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
        with ImplicitSender with Suite with ShouldMatchers with BeforeAndAfter
        with DeviceHandler {

    import TestVTPMActor._

    def handle(deviceId: UUID) { self ! HandleMsg(deviceId) }

    // this is the vtpm actor on which tests are run
    val vtpm = TestActorRef({ TestVTPMActor(self, this) }, "TestVTPMActor")

    // the testkit actor is reused between tests, therefore we need to drain
    // its mailbox between tests in case some tests fail to receive all msgs.
    before { while (msgAvailable) receiveOne(0 seconds) }

    def assertNoMsg { msgAvailable should not be (true) }

    def testSmoke() {
        val id = UUID.randomUUID()
        vtpm.underlyingActor.notifyLocalPortActive(id, true)
        expectMsg(NotifyLocalPortActive(id, true))
        assertNoMsg
    }

}
