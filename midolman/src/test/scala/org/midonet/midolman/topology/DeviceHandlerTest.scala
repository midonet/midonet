/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.midolman.topology

import java.util.UUID;

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.testkit.ImplicitSender

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.Suite
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

@RunWith(classOf[JUnitRunner])
class DeviceHandlerTest extends TestKit(ActorSystem("DeviceHandlerTests"))
        with ImplicitSender with Suite with ShouldMatchers with BeforeAndAfter
        with DeviceHandler {

    case class HandleMsg(id: UUID)

    val id = UUID.randomUUID()

    def handle(deviceId: UUID) { self ! HandleMsg(deviceId) }

    def testOneShotSubscribe() {
        val handler = new DeviceHandlersManager[String](this)

        // register self and expect handle msg
        handler.addSubscriber(id, self, false)
        expectMsg(HandleMsg(id))

        // notify subscribers and expect one msg
        handler.notifySubscribers(id, "whatever")
        expectMsg("whatever")

        // notify subscribers again but don't expect anything
        handler.notifySubscribers(id, "somethingelse")

        // resubcribe, notify, and expect a msg
        handler.addSubscriber(id, self, false)
        handler.notifySubscribers(id, "yetonemsg")
        expectMsg("yetonemsg")
    }

    def testSubscribeWithUpdate() {
        val handler = new DeviceHandlersManager[String](this)

        // register self for updates and expect handle msg
        handler.addSubscriber(id, self, true)
        expectMsg(HandleMsg(id))

        // notify subscribers and expect all msg
        val msgs = List("foo1","foo2","foo3")
        for (m <- msgs) { handler.notifySubscribers(id, "foo1") }
        for (m <- msgs) { expectMsg("foo1") }
    }

    def testUnsubscribe() {
        val handler = new DeviceHandlersManager[String](this)

        // unregister unregistered actor, nothing goes bad after that
        handler.removeSubscriber(id, self)

        // register self for updates and expect handle msg
        handler.addSubscriber(id, self, false)
        expectMsg(HandleMsg(id))

        // unregister, notify, dont get msg
        handler.removeSubscriber(id, self)
        handler.notifySubscribers(id, "wontreceive")

        // reregister with update, next msg should be from next notify
        handler.addSubscriber(id, self, true)
        handler.notifySubscribers(id, "willreceive")
        expectMsg("willreceive")

        // unregister, notify, dont get msg
        handler.removeSubscriber(id, self)
        handler.notifySubscribers(id, "wontreceive2")

        // rereregister with update, next msg should be from next notify
        handler.addSubscriber(id, self, true)
        handler.notifySubscribers(id, "willreceive2")
        expectMsg("willreceive2")
    }

}
