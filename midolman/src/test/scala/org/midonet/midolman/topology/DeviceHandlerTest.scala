/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.midolman.topology

import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import scala.concurrent.duration._
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, Matchers, BeforeAndAfter, Suite}
import org.scalatest.junit.JUnitRunner
import scala.collection.mutable

@RunWith(classOf[JUnitRunner])
class DeviceHandlerTest extends TestKit(ActorSystem("DeviceHandlerTests"))
        with ImplicitSender with Suite with Matchers with BeforeAndAfter
        with DeviceHandler with BeforeAndAfterAll {

    // the testkit actor is reused between test, therefore we need to drain
    // its mailbox between test in case some test fails to receive all msgs.
    before { while (msgAvailable) receiveOne(0 seconds) }

    override def afterAll() { system.shutdown() }

    case class HandleMsg(id: UUID)

    val id = UUID.randomUUID()
    val id2 = UUID.randomUUID()

    def assertNoMsg { msgAvailable should be (false) }

    def handle(deviceId: UUID) { self ! HandleMsg(deviceId) }

    def testClientStatus() {
        // testing all transitions from/to unknown to/from known state
        val devices: mutable.Map[UUID, String] = mutable.Map()
        val handler = new DeviceHandlersManager[String](this, devices.get, devices.put)

        handler.subscriberStatus(id, self) should be (None)

        handler.addSubscriber(id, self, false)
        expectMsg(HandleMsg(id))
        handler.subscriberStatus(id, self) should be (Some(false))

        handler.addSubscriber(id, self, true)
        handler.subscriberStatus(id, self) should be (Some(true))

        handler.removeSubscriber(id, self)
        handler.subscriberStatus(id, self) should be (None)

        handler.addSubscriber(id, self, true)
        handler.subscriberStatus(id, self) should be (Some(true))

        handler.addSubscriber(id, self, false)
        handler.subscriberStatus(id, self) should be (Some(false))

        handler.removeSubscriber(id, self)
        handler.subscriberStatus(id, self) should be (None)
    }

    def testOneShotSubscribe() {
        val devices: mutable.Map[UUID, String] = mutable.Map()
        val handler = new DeviceHandlersManager[String](this, devices.get, devices.put)

        // register self and expect handle msg
        handler.addSubscriber(id, self, false)
        expectMsg(HandleMsg(id))

        // notify subscribers and expect one msg
        handler.notifySubscribers(id, "whatever")
        expectMsg("whatever")

        // notify subscribers again but don't expect anything
        handler.notifySubscribers(id, "somethingelse")

        assertNoMsg

        // resubcribe, notify, and expect a msg
        handler.addSubscriber(id, self, false)
        handler.notifySubscribers(id, "yetonemsg")
        expectMsg("yetonemsg")
        assertNoMsg
    }

    def testSubscribeWithUpdate() {
        val devices: mutable.Map[UUID, String] = mutable.Map()
        val handler = new DeviceHandlersManager[String](this, devices.get, devices.put)

        // register self for updates and expect handle msg
        handler.addSubscriber(id, self, true)
        expectMsg(HandleMsg(id))

        // notify subscribers and expect all msg
        val msgs = List("foo1","foo2","foo3")
        for (m <- msgs) { handler.notifySubscribers(id, m) }
        for (m <- msgs) { expectMsg(m) }
        assertNoMsg
    }

    def testUnsubscribe() {
        val devices: mutable.Map[UUID, String] = mutable.Map()
        val handler = new DeviceHandlersManager[String](this, devices.get, devices.put)

        // unregister unregistered actor, nothing goes bad after that
        handler.removeSubscriber(id, self)

        // register self and expect handle msg
        handler.addSubscriber(id, self, false)
        expectMsg(HandleMsg(id))

        // unregister, notify, dont get msg
        handler.removeSubscriber(id, self)
        handler.notifySubscribers(id, "wontreceive")
        assertNoMsg

        // reregister with update, next msg should be from next notify
        handler.addSubscriber(id, self, true)
        handler.notifySubscribers(id, "willreceive")
        expectMsg("willreceive")

        // unregister, notify, dont get msg
        handler.removeSubscriber(id, self)
        handler.notifySubscribers(id, "wontreceive2")
        assertNoMsg

        // rereregister with update, next msg should be from next notify
        handler.addSubscriber(id, self, true)
        handler.notifySubscribers(id, "willreceive2")
        expectMsg("willreceive2")
        assertNoMsg
    }

    def testSubscribeChangeNoUpdateToUpdate() {
        val devices: mutable.Map[UUID, String] = mutable.Map()
        val handler = new DeviceHandlersManager[String](this, devices.get, devices.put)

        // register self as one shot and expect handle msg
        handler.addSubscriber(id, self, false)
        expectMsg(HandleMsg(id))

        assertNoMsg

        // changfe subscription to update=true
        handler.addSubscriber(id, self, true)

        // notify on id and expect all msg only once
        val msgs = List("foo1","foo2","foo3")
        for (m <- msgs) { handler.notifySubscribers(id, m) }
        for (m <- msgs) { expectMsg(m) }
        assertNoMsg
    }

    def testSubscribeChangeUpdateToNoUpdate() {
        val devices: mutable.Map[UUID, String] = mutable.Map()
        val handler = new DeviceHandlersManager[String](this, devices.get, devices.put)

        // subscribe with update and expect handle msg
        handler.addSubscriber(id, self, true)
        expectMsg(HandleMsg(id))

        // change subsciption to one shot
        handler.addSubscriber(id, self, false)

        // notify on id and expect only first msg once
        val msgs = List("foo1","foo2","foo3")
        for (m <- msgs) { handler.notifySubscribers(id, m) }
        expectMsg(msgs(0))
        assertNoMsg
    }

    def testSubscribeTwiceWithDevicePresent() {
        val devices: mutable.Map[UUID, String] = mutable.Map()
        val handler = new DeviceHandlersManager[String](this, devices.get, devices.put)

        // update a device, expect first handle msg
        handler.updateAndNotifySubscribers(id, "message1")
        expectMsg(HandleMsg(id))

        // subscribe with update, receive msg
        handler.addSubscriber(id, self, true)
        expectMsg("message1")

        // subscribe with update again, don't get msg
        handler.addSubscriber(id, self, true)
        assertNoMsg

        // update a device, get the msg
        handler.updateAndNotifySubscribers(id, "message2")
        expectMsg("message2")

        // unsubscribe, update device, don't get msg
        handler.removeSubscriber(id, self)
        handler.updateAndNotifySubscribers(id, "message3")
        assertNoMsg

        // resubscribe with update, get msg
        handler.addSubscriber(id, self, true)
        expectMsg("message3")

        // resubscribe without update, don't get msg
        handler.addSubscriber(id, self, false)
        assertNoMsg

        // send 3 msgs, only receive first one
        val msgs = List("foo1","foo2","foo3")
        for (m <- msgs) { handler.notifySubscribers(id, m) }
        expectMsg(msgs(0))
        assertNoMsg
    }

    def testMultipleIdOneSubscriber() {
        val devices: mutable.Map[UUID, String] = mutable.Map()
        val handler = new DeviceHandlersManager[String](this, devices.get, devices.put)

        val ids = List(id, id2)
        val msgs = List("msg_about_id1", "msg_about_id2")

        // register self for two different ids
        for (i <- ids) {
            handler.addSubscriber(i, self, true)
            expectMsg(HandleMsg(i))
        }

        // notify on both ids and get both msg
        for ((i,m) <- ids zip msgs) { handler.notifySubscribers(i, m) }
        for (m <- msgs) { expectMsg(m) }

        // unsubscribe from first id, only get msg from second id
        handler.removeSubscriber(ids(0), self)
        for ((i,m) <- ids zip msgs) { handler.notifySubscribers(i, m + "foo") }
        expectMsg(msgs(1) + "foo")
        assertNoMsg

        // resubscribe, notify and gets both msgs
        handler.addSubscriber(ids(0), self, true)
        for ((i,m) <- ids zip msgs) { handler.notifySubscribers(i, m + "bar") }
        for (m <- msgs) { expectMsg(m + "bar") }
        assertNoMsg
    }

    def testUpdateNotify() {
        val devices: mutable.Map[UUID, String] = mutable.Map()
        val handler = new DeviceHandlersManager[String](this, devices.get, devices.put)

        // update a device, expect first handle msg
        handler.updateAndNotifySubscribers(id, "message1")
        expectMsg(HandleMsg(id))

        assertNoMsg

        // subscribe as one shot, expect the msg
        handler.addSubscriber(id, self, false)
        expectMsg("message1")

        // reupdate the device
        handler.updateAndNotifySubscribers(id, "message2")

        assertNoMsg

        // subscribe with updates, expect the msg
        handler.addSubscriber(id, self, true)
        expectMsg("message2")

        assertNoMsg

        // reupdate the device, expect msg immediately
        handler.updateAndNotifySubscribers(id, "message3")
        expectMsg("message3")

        // unsubscribe, update, don't get a message
        handler.removeSubscriber(id, self)
        handler.updateAndNotifySubscribers(id, "message4")

        assertNoMsg

        // resubscribe as oneshot, get last message
        handler.addSubscriber(id, self, false)
        expectMsg("message4")
        assertNoMsg
    }
}
