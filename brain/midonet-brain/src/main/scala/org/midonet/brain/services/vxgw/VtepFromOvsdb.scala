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

package org.midonet.brain.services.vxgw

import java.lang.{Short => JShort}
import java.util
import java.util.UUID

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try}

import com.google.common.base.Strings
import com.google.common.base.Strings.isNullOrEmpty
import org.apache.commons.lang3.tuple.{Pair => JPair}
import org.opendaylight.controller.sal.utils.{Status, StatusCode}
import org.opendaylight.ovsdb.lib.notation.{UUID => OdlUUID}
import org.slf4j.LoggerFactory
import rx.{Observable, Observer}

import org.midonet.brain.southbound.vtep.model.{LogicalSwitch, McastMac, UcastMac}
import org.midonet.brain.southbound.vtep.{VtepBroker, VtepDataClientFactory, VtepMAC}
import org.midonet.packets.IPv4Addr

/** This class abstracts low-level details of the connection to an OVSDB
  * instance in order to satisfy the high-level interface used by the VxLAN
  * Gateway implementation to coordinate with a hardware vtep. */
class VtepFromOvsdb(ip: IPv4Addr, port: Int) extends VtepConfig(ip, port) {
    private val log = LoggerFactory.getLogger("VxGW: vtep " + ip + ":" + port )
    override def macLocalUpdates = Observable.empty[MacLocation]()
    override def macRemoteUpdater = new Observer[MacLocation]() {
        override def onCompleted (): Unit = {
            log.info (s"Inbound stream completed")
        }
        override def onError (e: Throwable): Unit = {
            log.error (s"Inbound stream completed")
        }
        override def onNext (ml: MacLocation): Unit = {
            log.error (s"VTEP $ip:$port learning remote: $ml")
        }
    }
    override def ensureBindings(lsName: String,
                                bindings: Iterable[(String, Short)]): Try[Unit] = ???
    override def ensureLogicalSwitch(name: String,
                                     vni: Int): Try[LogicalSwitch] = ???
    override def removeLogicalSwitch(name: String): Try[Unit] = ???
    override def currentMacLocal(ls: OdlUUID): Seq[MacLocation] = ???
    override def vxlanTunnelIp: Option[IPv4Addr] = ???
}

/** An implementation of the new VtepConfig interface that uses the old OVSDB
  * client and VtepBroker */
class VtepFromOldOvsdbClient(nodeId: UUID, ip: IPv4Addr, port: Int,
                             vtepDataClientFactory: VtepDataClientFactory)
    extends VtepConfig(ip, port) {

    private val log = LoggerFactory.getLogger(vxgwVtepControlLog(ip, port))

    private val ovsdbClient = vtepDataClientFactory.connect(ip, port, nodeId)

    private val oldVtepBroker = new VtepBroker(ovsdbClient)

    private val applyInOldBroker = new Observer[MacLocation] {
        override def onCompleted(): Unit = {
            log.info("Stream of MAC updates to VTEP is completed")
        }
        override def onError(e: Throwable): Unit = {
            log.warn("Error on stream of MAC updates to VTEP", e)
        }
        override def onNext(ml: MacLocation): Unit = {
            try {
                apply(ml)
            } catch {
                case e: VxlanGatewaySyncException if e.statusCode != null =>
                    log.warn(s"VTEP unreachable when applying $ml")
                case e: Throwable =>
                    log.warn(s"Could not apply $ml", e)
            }
        }
    }

    private def apply(ml: MacLocation): Unit = {
        if (ml == null) {
            return
        }
        log.debug(s"Remote MAC update: $ml")
        if (ml.mac.isUcast) {
            if (ml.isDeletion) applyUcastDelete(ml)
            else applyUcastAddition(ml)
        } else {
            if (ml.isDeletion) applyMcastDelete(ml)
            else applyMcastAddition(ml)
        }
    }

    /** Digests the result of a MacLocadtion addition */
    private def handleAdditionRes(st: Status, ml: MacLocation): Unit = {
        st.getCode match {
            case StatusCode.CONFLICT =>
                log.info(s"Unexpected conflict applying $ml")
            case code if !st.isSuccess =>
        }
    }

    /** Digests the result of a MacLocation removal */
    private def handleDeletionRes(st: Status, ml: MacLocation):Unit = {
        st.getCode match {
            case StatusCode.NOTFOUND => // well, ok
            case code if !st.isSuccess =>
                throw new VxlanGatewaySyncException(s"OVSDB error", ml, code)
        }
    }

    @inline
    private def isSame(ml: MacLocation, uc: UcastMac): Boolean = {
        if (uc.mac.equals(ml.mac.toString)) {
            true
        } else if (Strings.isNullOrEmpty(uc.ipAddr)) {
            ml.ipAddr == null
        } else {
            uc.ipAddr.equals(ml.ipAddr.toString)
        }
    }

    @inline
    private def isSame(ml: MacLocation, mc: McastMac): Boolean = {
        if (mc.mac.equals(ml.mac.toString)) {
            true
        } else if (Strings.isNullOrEmpty(mc.ipAddr)) {
            ml.ipAddr == null
        } else {
            mc.ipAddr.equals(ml.ipAddr.toString)
        }
    }

    private def applyMcastAddition(ml: MacLocation): Unit = {
        log.debug("Remote MCast MAC addition {}", ml)
        // This horrible thing is inefficient, but can't do better since the
        // OVSDB library doesn't expose its indexed cache, and building it
        // inside the ovsdbClient requires the same loop we have here..
        // TODO: revisit when we migrate to the new OVSDB client.
        val dupe = ovsdbClient.listMcastMacsLocal()
                              .collectFirst {case u: McastMac => isSame(ml, u)}
        if (dupe.isDefined) {
            log.debug("Skip duplicate MCast MAC addition {}", ml)
            return
        }
        handleAdditionRes (
            ovsdbClient.addMcastMacRemote(ml.logicalSwitchName,
                                          ml.mac,
                                          ml.vxlanTunnelEndpoint),
            ml
        )
    }

    private def applyMcastDelete(ml: MacLocation): Unit = {
        log.debug("Remote MCast MAC deletion {}", ml)
        handleDeletionRes (
            ovsdbClient.deleteAllMcastMacRemote(ml.logicalSwitchName, ml.mac),
            ml
        )
    }

    private def applyUcastAddition(ml: MacLocation): Unit = {
        log.debug("Remote UCast MAC addition {}", ml)
        log.debug("Remote MCast MAC addition {}", ml)
        // This horrible thing is inefficient, but can't do better since the
        // OVSDB library doesn't expose its indexed cache, and building it
        // inside the ovsdbClient requires the same loop we have here..
        // TODO: revisit when we migrate to the new OVSDB client.
        val dupe = ovsdbClient.listUcastMacsLocal()
                              .collectFirst {case u: UcastMac => isSame(ml, u)}
        if (dupe.isDefined) {
            log.debug("Skip duplicate UCast MAC addition {}", ml)
            return
        }
        handleAdditionRes (
            ovsdbClient.addUcastMacRemote(ml.logicalSwitchName,
                                          ml.mac.IEEE802(),
                                          ml.ipAddr,
                                          ml.vxlanTunnelEndpoint),
            ml
        )
    }

    private def applyUcastDelete(ml: MacLocation): Unit = {
        log.debug("Remote UCast MAC deletion {}", ml)
        val st = if (ml.ipAddr == null) {
            // removal with no IP, remove all mappings for the mac
            ovsdbClient.deleteAllUcastMacRemote(ml.logicalSwitchName,
                                                ml.mac.IEEE802())
        } else {
            // removal, but only for a given IP
            ovsdbClient.deleteUcastMacRemote(ml.logicalSwitchName,
                                             ml.mac.IEEE802, ml.ipAddr)
        }
        handleDeletionRes(st, ml)
    }

    /** Construct a MacLocation object with the given mac, ip and logical
      * switch ID, by fetching the tunnel IP and logical switch name and
      * building the MacLocation object with them */
    private def macLocation(mac: String, ip: String, lsId: OdlUUID)
    : Seq[MacLocation] = {
        val tunIp = ovsdbClient.getTunnelIp
        if (tunIp == null) {
            log.warn(s"VTEP's tunnel IP unknown, can't process mac")
            return Seq.empty
        }
        val ipAddr: IPv4Addr = try {
                                   if (isNullOrEmpty(ip)) null else IPv4Addr(ip)
                               } catch { case t: Throwable =>
                                   log.info(s"Failed to translate IP '$ip''", t)
                                   null
                               }
        val ls = ovsdbClient.getLogicalSwitch(lsId)
        Seq(MacLocation(VtepMAC.fromString(mac), ipAddr, ls.name, tunIp))
    }

    private def toMacLocation(ucast: UcastMac): Seq[MacLocation] = {
        macLocation(ucast.mac, ucast.ipAddr, ucast.logicalSwitch)
    }

    private def toMacLocation(mcast: McastMac): Seq[MacLocation] = {
        macLocation(mcast.mac, mcast.ipAddr, mcast.logicalSwitch)
    }

    override def macLocalUpdates
    : Observable[MacLocation] = oldVtepBroker.observableUpdates()

    override def macRemoteUpdater: Observer[MacLocation] = applyInOldBroker

    override def currentMacLocal(ls: OdlUUID): Seq[MacLocation] = {
        val macLocations = ListBuffer[MacLocation]()
        val ucastMacs = ovsdbClient.listUcastMacsLocal().iterator()
        while (ucastMacs.hasNext) {
            val ucm = ucastMacs.next()
            if (ucm.logicalSwitch.equals(ls)) {
                macLocations ++= toMacLocation(ucm)
            }
        }
        val mcastMacs = ovsdbClient.listMcastMacsLocal().iterator()
        while (mcastMacs.hasNext) {
            val mcm = mcastMacs.next()
            if (mcm.logicalSwitch.equals(ls)) {
                macLocations ++= toMacLocation(mcm)
            }
        }
        macLocations
    }

    override def removeLogicalSwitch(name: String): Try[Unit] = {
        val res = ovsdbClient.deleteLogicalSwitch(name)
        if (res.isSuccess) Success(Unit)
        else Failure(new VtepConfigException(res.toString))
    }

    override def ensureLogicalSwitch(name: String, vni: Int)
    : Try[LogicalSwitch] = {
        ovsdbClient.awaitConnected()
        val ls = ovsdbClient.getLogicalSwitch(name)
        if (ls == null) {
            val res = ovsdbClient.addLogicalSwitch(name, vni)
            if (res.isSuccess) {
                Success(ovsdbClient.getLogicalSwitch(res.getUuid))
            } else {
                val msg = "Failed to create logical switch " +
                         s"$name with vni $vni: ${res.toString}"
                Failure(new VtepConfigException(msg))
            }
        } else {
            Success(ls)
        }
    }
    override def vxlanTunnelIp: Option[IPv4Addr] = {
        Option(ovsdbClient.getTunnelIp)
    }

    override def ensureBindings(lsName: String,
                                bindings: Iterable[(String, Short)])
    : Try[Unit] = {
        val ls = ovsdbClient.getLogicalSwitch(lsName)
        if (ls == null) {
            return Failure(
                new VtepConfigException(s"Logical Switch $lsName not found")
            )
        }
        val jBindings: util.Collection[JPair[String, JShort]] =
            new util.ArrayList(bindings.size)
        bindings foreach { b => jBindings.add(JPair.of(b._1, b._2)) }
        val res = ovsdbClient.addBindings(ls.uuid, jBindings)
        if (res.isSuccess) {
            Success(Unit)
        } else {
            Failure(new VtepConfigException(s"Failed creating bindings: $res"))
        }
    }

}
