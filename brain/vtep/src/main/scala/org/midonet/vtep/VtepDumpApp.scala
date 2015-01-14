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

import org.midonet.packets.IPv4Addr
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * An application to dump Midonet's view of VTEP information
 */
object VtepDumpApp extends App {
    private final val log = LoggerFactory.getLogger(this.getClass)
    private final val mgmtIp: IPv4Addr = IPv4Addr.fromString(args(0))
    private final val mgmtPort: Int = args(1).toInt
    private final val vtep = VtepEndPoint(mgmtIp, mgmtPort)

    private val owner = UUID.randomUUID()
    private val ovsdbVtep = new OvsdbDataClient(vtep)

    sys.addShutdownHook {
        if (ovsdbVtep != null) {
            log.info("terminating vtep connection")
            Await.ready(ovsdbVtep.disconnect(owner), 5.seconds)
        }
    }

    try {
        log.info("connecting to vtep: " + vtep)
        Await.ready(ovsdbVtep.connect(owner), 5.seconds)
        log.info("vtep status: " + ovsdbVtep.getState)
        log.info("vtep tunnel ip: " + ovsdbVtep.vxlanTunnelIp)
        log.info("logical switches:")
        for (ls <- ovsdbVtep.getLogicalSwitches) {
            log.info("ls: " + ls)
        }
        log.info("physical locators:")
        for (pl <- ovsdbVtep.getPhysicalLocators) {
            log.info("pl: " + pl)
        }
        log.info("physical locator sets:")
        for (pls <- ovsdbVtep.getPhysicalLocatorSets) {
            log.info("pls " + pls._1 + ": " + pls._2)
        }
        log.info("local ucast macs:")
        for (mac <- ovsdbVtep.getLocalUcastMacs) {
            log.info("mac: " + mac)
        }
        log.info("remote ucast macs:")
        for (mac <- ovsdbVtep.getRemoteUcastMacs) {
            log.info("mac: " + mac)
        }
        Thread.sleep(10000)
        log.info("disconnecting from vtep: " + vtep)
        Await.ready(ovsdbVtep.disconnect(owner), 5.seconds)
    } catch {
        case e: Exception =>
            log.error("vtep exception: " + vtep, e)
    }
}
