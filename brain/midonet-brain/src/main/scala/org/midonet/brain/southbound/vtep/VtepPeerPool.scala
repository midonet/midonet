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

package org.midonet.brain.southbound.vtep

import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._

import com.google.common.annotations.VisibleForTesting
import org.slf4j.LoggerFactory
import rx.subjects.PublishSubject
import rx.{Observer, Subscription}

import org.midonet.brain.services.vxgw.MacLocation
import org.midonet.brain.southbound.midonet.LogicalSwitch
import org.midonet.packets.IPv4Addr

/** A Vxlan Tunnel End Point that can join in Logical switches */
abstract class VtepPeer {

    /** Make this VTEP peer participate in the given Logical Switch */
    def join(logicalSwitch: LogicalSwitch, preseed: Iterable[MacLocation])

    /** Make this VTEP abandon the given Logical Switch */
    def abandon(logicalSwitch: LogicalSwitch)

    /** LogicalSwitches in which this VTEP participates */
    def memberships: Iterator[LogicalSwitch]
}

class VtepPeerPool {

    private val pool = new ConcurrentHashMap[IPv4Addr, VtepPeer]()

    def fish(mgmtIp: IPv4Addr, mgmtPort: Int): VtepPeer = {
        pool.get(mgmtIp) match {
            case null =>
                val newPeer = create(mgmtIp, mgmtPort)
                val previous = pool.putIfAbsent(mgmtIp, newPeer)
                if (previous == null) { // TODO: cleanup the created peer?
                    newPeer
                } else {
                    previous
                }
            case vtep => vtep
        }
    }

    def fishIfExists(mgmtIp: IPv4Addr, mgmtPort: Int): Option[VtepPeer] = {
        Option(pool.get(mgmtIp))
    }

    @VisibleForTesting
    def create(mgmtIp: IPv4Addr, mgmtPort: Int): VtepPeer = {
        new VtepPeerImpl(mgmtIp, mgmtPort)
    }

}

class VtepPeerImpl(ip: IPv4Addr, port: Int) extends VtepPeer {

    private val log = LoggerFactory.getLogger("VxGW VTEP broker")

    /* This is our firehose to publish updates in our VTEP */
    private val myStream = PublishSubject.create[MacLocation]()

    private val subscriptions =
        new ConcurrentHashMap[LogicalSwitch, LogicalSwitchSubscriptions]

    /** This class contains all the subscriptions that link this VTEP to the
      * Logical Switch message bus. It helps with thread safety. */
    private class LogicalSwitchSubscriptions {
        var toBus: Subscription = _
        var fromBus: Subscription = _
    }

    @VisibleForTesting // allow overrides so tests can tap here
    protected val inbound = new Observer[MacLocation] {
        override def onCompleted(): Unit = {
            log.info(s"Inbound stream completed")
        }
        override def onError(e: Throwable): Unit = {
            log.error(s"Inbound stream completed")
        }
        override def onNext(ml: MacLocation): Unit = {
            log.error(s"VTEP $ip:$port learning remote: $ml")
        }
    }

    override def join(ls: LogicalSwitch, preseed: Iterable[MacLocation])
    : Unit = {

        log.info(s"VTEP $ip:$port joins $ls, ${preseed.size} primed entries}")

        consolidate()

        val newSub = new LogicalSwitchSubscriptions
        val nullIfAdded = subscriptions.putIfAbsent(ls, newSub)
        if (nullIfAdded != null) {
            log.info(s"VTEP $ip:$port already part of $ls")
            return
        }

        // Subscribe the bus to our stream so we publish everything into it
        newSub.toBus = myStream.subscribe(ls.asObserver)

        // Subscribe to the bus, filtering out MacLocations on our own VTEP
        val bus = ls.asObservable.startWith(preseed.asJava)
        newSub.fromBus =  bus//.filter(makeFunc1 { _.vxlanTunnelEndpoint ne ip } )
                             .subscribe(inbound)

    }

    /** LogicalSwitches in which this VTEP participates */
    override def memberships: Iterator[LogicalSwitch] = subscriptions.keys().asScala

    override def abandon(ls: LogicalSwitch): Unit = {
        val curr = subscriptions.remove(ls)
        if (curr != null) {
            log.info(s"VTEP at ($ip:$port) no longer part of $ls")
            curr.fromBus.unsubscribe()
            curr.toBus.unsubscribe()
            cleanupVtep()
        }
    }

    private def consolidate(): Unit = {
        log.error("VTEP CONSOLIDATION NOT IMPLEMENTED")
        // TODO: at this point we should setup a monitor in ZK to see
        // whether bindings appear or disappear, and consolidate the
        // VTEP. The API does this by itself, and since the VXGW service
        // runs inside the API, in practise any write failure from the
        // API will also affect us.  The consolidation will happen
        // always when restarting.
    }

    private def cleanupVtep(): Unit = {
        log.error("VTEP CLEANUP NOT IMPLEMENTED")
    }

}
