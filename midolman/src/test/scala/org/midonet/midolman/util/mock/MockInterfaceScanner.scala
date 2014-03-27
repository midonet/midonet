/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.util.mock

import java.util.{Set => JSet}
import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.JavaConversions._
import scala.collection.concurrent

import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.Subscription
import org.midonet.netlink.Callback

class MockInterfaceScanner extends InterfaceScanner {
    private val interfaces = concurrent.TrieMap[String, InterfaceDescription]()
    private val callbacks = new ConcurrentLinkedQueue[Callback[JSet[InterfaceDescription]]]()

    def addInterface(itf: InterfaceDescription): Unit = {
        interfaces.put(itf.getName, itf)
        runCallbacks()
    }

    def removeInterface(name: String): Unit = {
        interfaces.remove(name)
        runCallbacks()
    }

    def register(callback: Callback[JSet[InterfaceDescription]]): Subscription = {
        callbacks.add(callback)
        doCallback(callback)
        new Subscription {
            override def unsubscribe(): Unit = callbacks.remove(callback)
            override def isUnsubscribed: Boolean = !callbacks.contains(callback)
        }
    }

    def runCallbacks(): Unit = callbacks foreach doCallback

    def doCallback(cb: Callback[JSet[InterfaceDescription]]): Unit =
        cb.onSuccess(interfaces.values.toSet[InterfaceDescription])

    def start(): Unit = { }
    def shutdown(): Unit = { }
}
