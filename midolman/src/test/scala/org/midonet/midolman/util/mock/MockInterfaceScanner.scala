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
