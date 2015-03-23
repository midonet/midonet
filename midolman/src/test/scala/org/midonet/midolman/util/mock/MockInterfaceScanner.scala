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

import java.nio.ByteBuffer
import java.util.{Set => JSet}

import scala.collection.concurrent

import rx.subjects.BehaviorSubject
import rx.{Observable, Observer, Subscription}

import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.netlink.rtnetlink.{Addr, Link, Neigh, Route}
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.functors._

class MockInterfaceScanner extends InterfaceScanner {

    private val interfaces = concurrent.TrieMap[String, InterfaceDescription]()
    private val dummyNotification = ByteBuffer.allocate(1)

    def addInterface(itf: InterfaceDescription): Unit = {
        interfaces.put(itf.getName, itf)
        notificationSubject.onNext(dummyNotification)
    }

    def removeInterface(name: String): Unit = {
        interfaces.remove(name)
        notificationSubject.onNext(dummyNotification)
    }

    private val notificationSubject = BehaviorSubject.create[ByteBuffer]()

    private val notifications: Observable[Set[InterfaceDescription]] =
        notificationSubject.map(makeFunc1(_ => interfaces.values.toSet))

    override
    def subscribe(obs: Observer[Set[InterfaceDescription]]): Subscription =
        notifications.subscribe(obs)

    private def noop[T](observer: Observer[T], content: T): Unit = {
        observer.onNext(content)
        observer.onCompleted()
    }

    override
    def routesCreate(dst: IPv4Addr, prefix: Int, gw: IPv4Addr,
                     link: Link, observer: Observer[Boolean]): Unit =
        noop(observer, true)

    override
    def linksSet(link: Link, observer: Observer[Boolean]): Unit =
        noop(observer, true)

    override
    def linksList(observer: Observer[Set[Link]]): Unit =
        noop(observer, Set.empty[Link])

    /*    def routesDel(route: Route, observer: Observer[Boolean]): Unit */

    override
    def neighsList(observer: Observer[Set[Neigh]]): Unit =
        noop(observer, Set.empty[Neigh])

    override
    def linksGet(ifindex: Int, observer: Observer[Link]): Unit =
        noop(observer, new Link)

    override
    def routesGet(dst: IPv4Addr, observer: Observer[Route]): Unit =
        noop(observer, new Route)

    // def addrsGet(ifIndex: Int, observer: Observer[Addr]): Unit =

    override
    def addrsGet(ifIndex: Int, observer: Observer[Set[Addr]]): Unit =
        noop(observer, Set.empty[Addr])

    override
    def addrsGet(ifIndex: Int, family: Byte,
                          observer: Observer[Set[Addr]]): Unit =
        noop(observer, Set.empty[Addr])

    override
    def linksSetAddr(link: Link, mac: MAC,
                     observer: Observer[Boolean]): Unit =
        noop(observer, true)

    /*    def addrsCreate(addr: Addr, observer: Observer[Boolean]): Unit */

    override
    def routesList(observer: Observer[Set[Route]]): Unit =
        noop(observer, Set.empty[Route])

    /*    def linksDel(link: Link, observer: Observer[Boolean]): Unit */

    override
    def addrsList(observer: Observer[Set[Addr]]): Unit =
        noop(observer, Set.empty[Addr])

    override
    def linksCreate(link: Link, observer: Observer[Link]): Unit =
        noop(observer, link)

    override def start(): Unit = {}

    override def stop(): Unit = {}
}

