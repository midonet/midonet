/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.vtep

import java.util.UUID
import java.util.concurrent.CountDownLatch

import scala.concurrent.duration._

import org.slf4j.LoggerFactory
import rx.Observer

import org.midonet.cluster.data.vtep.VtepConnection.State
import org.midonet.cluster.data.vtep.model.{MacLocation, VtepEndPoint}
import org.midonet.packets.IPv4Addr

/**
 * An application to dump Midonet's view of VTEP information.
 * This is mostly intended for debugging.
 */
object VtepDumpApp extends App {
    private final val log = LoggerFactory.getLogger(this.getClass)
    private final val mgmtIp: IPv4Addr = IPv4Addr.fromString(args(0))
    private final val mgmtPort: Int = args(1).toInt
    private final val continuous: Boolean =
        args.length >= 3 && args(2) == "--continuous"
    private final val vtep = VtepEndPoint(mgmtIp, mgmtPort)

    private val owner = UUID.randomUUID()
    private val ovsdbVtep = new OvsdbVtepDataClient(vtep, 0, 0)

    sys.addShutdownHook {
        if (ovsdbVtep != null) {
            log.info("terminating vtep connection")
            ovsdbVtep.disconnect(owner)
            ovsdbVtep.awaitState(Set(State.DISCONNECTED, State.DISPOSED),
                                 5.seconds)
        }
    }

    try {
        log.info("connecting to vtep: " + vtep)
        ovsdbVtep.connect(owner)
        ovsdbVtep.awaitState(Set(State.READY), 5.seconds)
        log.info("vtep management ip: ", ovsdbVtep.getManagementIp)
        log.info("vtep management port: ", ovsdbVtep.getManagementPort)
        log.info("vtep status: " + ovsdbVtep.getState)
        log.info("vtep tunnel ip: " + ovsdbVtep.vxlanTunnelIp)
        ovsdbVtep.listPhysicalSwitches.foreach(ps => {
            log.info("physical switch: " + ps)
            ovsdbVtep.physicalPorts(ps.uuid).foreach(p => {
                log.info("physical port: " + p)
            })
        })
        ovsdbVtep.listLogicalSwitches.foreach(ls => {
            log.info("logical switch: " + ls)
        })
        log.info("local macs:")
        ovsdbVtep.currentMacLocal.foreach(ml => {
            log.info("mac location: " + ml)
        })
        if (continuous) {
            val subs1 = ovsdbVtep.macLocalUpdates.subscribe(
                new Observer[MacLocation] {
                override def onCompleted(): Unit =
                    log.warn("mac updates completed")
                override def onError(e: Throwable): Unit =
                    log.error("mac updates failed", e)
                override def onNext(ml: MacLocation): Unit =
                    log.info("mac location update: " + ml)
                    private val termination = new CountDownLatch(1)
            })
            val subs2 = ovsdbVtep.observable.subscribe( new Observer[State.Value] {
                override def onCompleted(): Unit =
                    log.warn("state observable completed")
                override def onError(e: Throwable): Unit =
                    log.error("state observable failed")
                override def onNext(st: State.Value): Unit =
                    log.info("vtep state update: ", st)
                }
            )
            val termination = new CountDownLatch(1)
            try {
                termination.await()
            } catch {
                case e: InterruptedException =>
                    log.info("terminating vtep dump")
                    Thread.currentThread().interrupt()
                case e: Exception =>
                    log.error("vtep dump failed")
            } finally {
                subs1.unsubscribe()
                subs2.unsubscribe()
            }
        }
        log.info("disconnecting from vtep: " + vtep)
        ovsdbVtep.disconnect(owner)
        ovsdbVtep.awaitState(Set(State.DISCONNECTED, State.DISPOSED),
                             5.seconds)
    } catch {
        case e: Exception =>
            log.error("vtep exception: " + vtep, e)
    }
}
