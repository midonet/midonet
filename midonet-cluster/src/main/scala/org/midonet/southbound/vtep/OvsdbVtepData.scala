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

package org.midonet.southbound.vtep

import java.util.UUID
import java.util.concurrent.Executor

import scala.collection.Iterable
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import com.typesafe.scalalogging.Logger
import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.slf4j.LoggerFactory
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.{Observable, Observer, Subscriber}

import org.midonet.cluster.data.vtep.model._
import org.midonet.cluster.data.vtep.{VtepConfigException, VtepStateException}
import org.midonet.packets.IPv4Addr
import org.midonet.southbound.vtepLog
import org.midonet.southbound.vtep.OvsdbOperations._
import org.midonet.southbound.vtep.OvsdbUtil.panicAlert
import org.midonet.southbound.vtep.OvsdbVtepData.{NamedLocatorId, NamedLocatorSetId}
import org.midonet.southbound.vtep.schema.Table.OvsdbOperation
import org.midonet.southbound.vtep.schema._
import org.midonet.util.concurrent._
import org.midonet.util.functors.makeFunc1

object OvsdbVtepData {

    private[vtep] final val NamedLocatorId = "locator_id"
    private[vtep] final val NamedLocatorSetId = "locator_set_id"

}

/**
 * This class handles the data from an OVSDB-compliant VTEP.
 */
class OvsdbVtepData(val client: OvsdbClient, val dbSchema: DatabaseSchema,
                    val vtepExecutor: Executor, val eventExecutor: Executor)
    extends VtepData {

    private val endPoint = OvsdbTools.endPointFromOvsdbClient(client)

    private val MaxBackpressureBuffer = 100000

    private val log =
        Logger(LoggerFactory.getLogger(vtepLog(endPoint.mgmtIp, endPoint.mgmtPort)))
    private implicit val vtepContext = ExecutionContext.fromExecutor(vtepExecutor)
    private val vtepScheduler = Schedulers.from(vtepExecutor)

    case class ConfigException(msg: String)
        extends VtepConfigException(endPoint, msg)

    private def cachedTable[E <: VtepEntry](table: Table[E])
    : OvsdbCachedTable[E] = {
        new OvsdbCachedTable[E](client, table, vtepExecutor, eventExecutor)
    }

    private val lsTable = cachedTable(new LogicalSwitchTable(dbSchema))
    private val psTable = cachedTable(new PhysicalSwitchTable(dbSchema))
    private val portTable = cachedTable(new PhysicalPortTable(dbSchema))
    private val locSetTable = cachedTable(new PhysicalLocatorSetTable(dbSchema))
    private val locTable = cachedTable(new PhysicalLocatorTable(dbSchema))
    private val uLocalTable = cachedTable(new UcastMacsLocalTable(dbSchema))
    private val uRemoteTable = cachedTable(new UcastMacsRemoteTable(dbSchema))
    private val mLocalTable = cachedTable(new McastMacsLocalTable(dbSchema))
    private val mRemoteTable = cachedTable(new McastMacsRemoteTable(dbSchema))

    private val uLocalMonitor = new OvsdbTableMonitor[UcastMac](
        client, uLocalTable.table)(eventExecutor)
    private val mLocalMonitor = new OvsdbTableMonitor[McastMac](
        client, mLocalTable.table)(eventExecutor)

    private val tables = List(lsTable, psTable, portTable,
                              locSetTable, locTable,
                              uLocalTable, uRemoteTable,
                              mLocalTable, mRemoteTable)

    private val ready: Future[Boolean] =
        Future.reduce[Boolean, Boolean](
            tables.map(_.ready))(_ && _)

    private val macUpdateToMacLocationsFunc =
        makeFunc1[VtepTableUpdate[_ <: MacEntry], Observable[MacLocation]] {
            entry => vxlanTunnelIp.asObservable(vtepContext)
                .flatMap(makeFunc1 { someTunnelIp: Option[IPv4Addr] =>
                Observable.from(macUpdateToMacLocations(entry, someTunnelIp).asJava)
            })
        }

    /** Returns all physical switches. */
    override def physicalSwitches: Future[Seq[PhysicalSwitch]] = {
        onReady { psTable.getAll.toSeq }
    }

    /** Gets the physical switch corresponding to the current VTEP endpoint. */
    override def physicalSwitch: Future[Option[PhysicalSwitch]] = {
        onReady {
            val entries = psTable.getAll
            entries.find(_.mgmtIps.contains(endPoint.mgmtIp)).orElse {
                // See MNA-800.  If we find the PhysicalSwitch entry with the
                // right management IP that's great.  Otherwise, just take the
                // head, as some VTEPs don't populate the management IP (e.g.,
                // HP)
                entries.headOption
            }
        }
    }

    /** Lists all logical switches. */
    override def logicalSwitches: Future[Seq[LogicalSwitch]] = {
        onReady { lsTable.getAll.toSeq }
    }

    /** Gets the logical switch with the specified name. */
    override def logicalSwitch(name: String): Future[Option[LogicalSwitch]] = {
        onReady { lsTable.getAll.find(_.name == name) }
    }

    /** Creates a new logical switch with the specified name and VNI. If a
      * logical switch with the same name and VNI already exists, the method
      * succeeds immediately. */
    override def createLogicalSwitch(name: String, vni: Int)
    : Future[UUID] = {
        logicalSwitch(name) flatMap {
            case Some(ls) if ls.tunnelKey == vni => Future.successful(ls.uuid)
            case Some(ls) =>
                Future.failed(new OvsdbException(
                    client,
                    s"A VTEP with name `$name` already exists but it has" +
                    s"VNI ${ls.tunnelKey}"))
            case None => lsTable.insert(LogicalSwitch(name, vni, ""))
        }
    }

    /** Deletes the logical switch, along with all its bindings and MAC
      * entries.
      */
    override def deleteLogicalSwitch(id: UUID): Future[Int] = {
        val bindings = portTable.getAll
            .filter(_.isBoundToLogicalSwitchId(id))
            .map(_.clearBindings(id))
        val ops = new ArrayBuffer[OvsdbOperation](bindings.size + 5)

        // Clean-up the MAC tables.
        List(
            uLocalTable, uRemoteTable, mLocalTable, mRemoteTable
        ).foreach { t =>
            ops += t.table.asInstanceOf[MacsTable[_]]
                          .deleteByLogicalSwitchId(id)
        }

        // Clear the bindings.
        bindings foreach { port =>
            ops += portTable.table.asInstanceOf[PhysicalPortTable]
                                  .updateBindings(port)
        }

        // Delete the logical switch.
        ops += lsTable.table.deleteById(id)

        multiOp(client, dbSchema, ops)(vtepExecutor) map {
            _.foldLeft(0)(_ + _.getCount)
        }
    }

    /** Lists all physical ports. */
    override def physicalPorts: Future[Seq[PhysicalPort]] = {
        onReady { portTable.getAll.toSeq }
    }

    /** Gets the physical port with the specified port identifier. */
    override def physicalPort(portId: UUID): Future[Option[PhysicalPort]] = {
        onReady { portTable.get(portId) }
    }

    /** Adds the bindings for the logical switch with the specified name. The
      * bindings are specified as an [[Iterable]] of port name and VLAN pairs.
      * The methds does not change any existing bindings for the specified
      * physical ports. */
    override def addBindings(lsId: UUID, bindings: Iterable[(String, Short)])
    : Future[Int] = {
        updateBindings(lsId, ps => {
            val boundPorts = bindings.map(_._1).toSet
            var ports = ps.ports.flatMap(portTable.get)
                                .filter(p => boundPorts.contains(p.name))
                                .map(p => (p.name, p)).toMap
            for (binding <- bindings) ports.get(binding._1) match {
                case None =>
                    log.warn("Physical port {} not found", binding._1)
                case Some(p) =>
                    ports = ports updated (p.name, p.addBinding(binding._2.toInt,
                                                                lsId))
            }
            ports.values.toSeq
        })
    }

    /** Sets the bindings for the logical switch with the specified name. The
      * bindings are specified as an [[Iterable]] of port name and VLAN pairs.
      * The method overwrites any of the previous bindings for the specified
      * logical switch, and replaces them with the given ones. The method
      * returns a future with the number of physical ports that were changed. */
    override def setBindings(lsId: UUID, bindings: Iterable[(String, Short)])
    : Future[Int] = {
        updateBindings(lsId, ps => {
            val boundPorts = bindings.map(_._1).toSet
            var ports = ps.ports.flatMap(portTable.get)
                                .filter(p => p.isBoundToLogicalSwitchId(lsId) ||
                                             boundPorts.contains(p.name))
                                .map(p => (p.name, p.clearBindings(lsId))).toMap

            for (binding <- bindings) ports.get(binding._1) match {
                case None =>
                    log.warn("Physical port {} not found", binding._1)
                case Some(p) =>
                    ports = ports updated (p.name, p.addBinding(binding._2.toInt,
                                                                lsId))
            }
            ports.values.toSeq
        })
    }

    /** Clears all bindings for the specified logical switch name. */
    override def clearBindings(lsId: UUID): Future[Int] = {
        updateBindings(lsId, ps => {
            ps.ports.flatMap(portTable.get)
                    .filter(_.isBoundToLogicalSwitchId(lsId))
                    .map(_.clearBindings(lsId))
                    .toSeq
        })
    }

    /** Returns an [[Observable]] that emits updates for the `Ucast_Mac_Local`
      * and `Mcast_Mac_Local` tables, with the MACs that are local to the VTEP
      * and should be published to other members of a VxLAN gateway. */
    override def macLocalUpdates: Observable[MacLocation] =
        Observable.merge(uLocalMonitor.observable, mLocalMonitor.observable)
            .onBackpressureBuffer(MaxBackpressureBuffer, panicAlert(log))
            .observeOn(vtepScheduler)
            .concatMap(macUpdateToMacLocationsFunc)

    /** Returns an [[Observer]] that will write updates to the local MACs in the
      * `Ucast_Mac_Local` or `Mcast_Mac_Local` tables. */
    override def macLocalUpdater: Future[Observer[MacLocation]] = {
        macUpdater(new MacUpdater("local", uLocalTable, mLocalTable))
    }

    /** Returns an [[Observer]] that will write updates to the remote MACs in
      * the `Ucast_Mac_Remote` or `Mcast_Mac_Remote` tables. */
    override def macRemoteUpdater: Future[Observer[MacLocation]] = {
        macUpdater(new MacUpdater("remote", uRemoteTable, mRemoteTable))
    }

    /** Provides a snapshot of the `Ucast_Mac_Local` and `Mcast_Mac_Local`
      * tables. */
    override def currentMacLocal: Future[Seq[MacLocation]] = {
        currentMac(uLocalTable, mLocalTable)
    }

    /** Provides a snapshot of the `Ucast_Mac_Remote` and `Mcast_Mac_Remote`
      * tables. */
    override def currentMacRemote: Future[Seq[MacLocation]] = {
        currentMac(uRemoteTable, mRemoteTable)
    }

    private class MacUpdater(`type`: String,
                             ucastTable: OvsdbCachedTable[UcastMac],
                             mcastTable: OvsdbCachedTable[McastMac])
        extends Subscriber[MacLocation] {

        override def onStart(): Unit = request(1)
        override def onCompleted(): Unit = {
            log.debug("Closed stream of {} MAC updates", `type`)
            unsubscribe()
        }
        override def onError(err: Throwable): Unit = {
            log.warn("Error on stream of {} MAC updates", `type`, err)
            unsubscribe()
        }
        override def onNext(ml: MacLocation): Unit = {
            log.debug("Publishing {} MAC to VTEP: {}", `type`, ml)
            if (ml != null) {
                applyMac(ml)
            }
            request(1)
        }

        private def applyMac(ml: MacLocation): Unit = {
            logicalSwitch(ml.logicalSwitchName) map {
                case None =>
                    log.warn("Unknown logical switch for {} MAC update: {}",
                             `type`, ml.logicalSwitchName)
                    Seq.empty

                case Some(ls) if ml.vxlanTunnelEndpoint == null =>
                    deleteMac(ml, ls)

                case Some(ls) if ml.mac.isUcast =>
                    addUcastMac(ml, ls)

                case Some(ls) =>
                    addMcastMac(ml, ls)
            } flatMap {
                OvsdbOperations.multiOp(client, dbSchema, _)
            } onComplete {
                case Success(s) =>
                    log.trace("MAC {} tables updated successfully: {}", `type`, s)
                case Failure(e) =>
                    log.warn("Updating {} MAC tables failed", `type`, e)
            }
        }

        /** Returns the OVSDB operations to add a unicast MAC entry. If a
          * locator already exist for the tunnel IP address, the method will use
          * that locator to INSERT the MAC entry. Otherwise, the method will
          * INSERT a new locator with a named-UUID, and INSERT the MAC
          * referencing the new locator (both operations are executed in the
          * same transaction).
          *
          * If an entry for the same MAC address already exists, the method
          * removes the previous one and replaces it with the new one.
          *
          * Note: It is important that the locator and MAC entry are created
          * in the same transaction. Otherwise, the locator is automatically
          * deleted because there is no MAC entry referencing it. */
        private def addUcastMac(ml: MacLocation, ls: LogicalSwitch)
        : Seq[Table.OvsdbOperation] = {
            var ops = Seq.empty[OvsdbOperation]

            // Get or create the locator ID for the VXLAN tunnel end-point.
            val locatorId = getOrCreateLocator(ml.vxlanTunnelEndpoint,
                                               op => ops = ops :+ op)
            // If the MAC entry for the same location already exists,
            // return no ops.
            if (ucastTable.getAll.exists(e => e.ls == ls.uuid &&
                                              e.macAddr == ml.mac &&
                                              e.ipAddr == ml.ipAddr &&
                                              e.locatorId == locatorId)) {
                return Seq.empty
            }

            // Remove all other MAC entries for the same logical switch and MAC.
            // Note: Disable this to allow multiple MAC entries for the same
            // logical switch.
            ops = ops ++: ucastTable.getAll
                .filter(e => e.ls == ls.uuid && e.macAddr == ml.mac)
                .map(e => ucastTable.table.delete(
                    UcastMac(ls.uuid, ml.mac, e.ipAddr, loc = null)))
                .toSeq

            // Insert the new MAC entry.
            ops = ops :+ ucastTable.table.insert(
                UcastMac(ls.uuid, ml.mac, ml.ipAddr, locatorId), null /* ID */)

            ops
        }

        /** Returns the OVSDB operations to add a multicast MAC entry. If a
          * locator and locator set already exist for the tunnel IP address,
          * the method will use that locator and locator set. Otherwise, the
          * method will INSERT a new locator and locator set as needed in the
          * same transaction that creates the MAC entry.
          *
          * If an entry for the same MAC address already exists, the method
          * removes the previous one and replaces it with the new one.
          */
        private def addMcastMac(ml: MacLocation, ls: LogicalSwitch)
        : Seq[Table.OvsdbOperation] = {
            var ops = Seq.empty[OvsdbOperation]

            // Get or create the locator ID for the VXLAN tunnel end-point.
            val locatorId = getOrCreateLocator(ml.vxlanTunnelEndpoint,
                                               op => ops = ops :+ op)

            // Get or create the locator set ID for the previous locator.
            val locatorSetId = getOrCreateLocatorSet(locatorId,
                                                     op => ops = ops :+ op)

            // If the MAC entry for the same location already exists,
            // return no ops.
            if (mcastTable.getAll.exists(e => e.ls == ls.uuid &&
                                              e.macAddr == ml.mac &&
                                              e.ipAddr == ml.ipAddr &&
                                              e.locatorId == locatorId)) {
                return Seq.empty
            }

            // Remove all other MAC entries for the same logical switch and MAC.
            // Note: Disable this to allow multiple MAC entries for the same
            // logical switch.
            ops = ops ++: mcastTable.getAll
                .filter(e => e.ls == ls.uuid && e.macAddr == ml.mac)
                .map(e => mcastTable.table.delete(
                    McastMac(ls.uuid, ml.mac, e.ipAddr, loc = null)))
                .toSeq

            // Insert the new MAC entry.
            ops = ops :+ mcastTable.table.insert(
                McastMac(ls.uuid, ml.mac, ml.ipAddr, locatorSetId), null /* ID */)

            ops
        }

        /** Returns the OVSDB operations to delete a unicast or multicast MAC
          * entry from MAC tables (local or remote) of this [[MacUpdater]] */
        private def deleteMac(ml: MacLocation, ls: LogicalSwitch)
        : Seq[Table.OvsdbOperation] = {
            if (ml.mac.isUcast) {
                Seq(ucastTable.table.delete(
                    UcastMac(ls.uuid, ml.mac, ml.ipAddr, loc = null)))
            } else {
                Seq(mcastTable.table.delete(
                    McastMac(ls.uuid, ml.mac, ml.ipAddr, loc = null)))
            }
        }

        /** Gets the locator identifier for the specified tunnel IP address. If
          * the locator does not exist, the method creates a new OVSDB insert
          * operation for the new locator, and returns its named identifier. */
        private def getOrCreateLocator(tunnelIp: IPv4Addr,
                                       updater: (OvsdbOperation) => Unit)
        : String = {
            locTable.getAll.find(_.dstIp == tunnelIp) match {
                case Some(locator) => locator.uuid.toString
                case None =>
                    updater(locTable.table.insert(
                        PhysicalLocator(tunnelIp), NamedLocatorId))
                    NamedLocatorId
            }
        }

        /** Gets the locator set identifier that contains the given locator
          * identifier. If the locator set does not exist, the method creates
          * a new OVSDB insert operation for the new locator set, and returns
          * its named identifier. */
        private def getOrCreateLocatorSet(locatorId: String,
                                          updater: (OvsdbOperation) => Unit)
        : String = {
            locSetTable.getAll.find(_.locatorIds.contains(locatorId)) match {
                case Some(locatorSet) => locatorSet.uuid.toString
                case None =>
                    updater(locSetTable.table.insert(
                        PhysicalLocatorSet(locatorId), NamedLocatorSetId))
                    NamedLocatorSetId
            }
        }
    }

    private def onReady[T](f: => T): Future[T] = {
        ready.map { r =>
            if (r) f
            else throw new VtepStateException(endPoint, "Reading VTEP data failed")
        }
    }

    private def vxlanTunnelIp: Future[Option[IPv4Addr]] = {
        physicalSwitch map { _.flatMap(_.tunnelIp) }
    }

    private def updateBindings(lsId: UUID,
                               updater: PhysicalSwitch => Seq[PhysicalPort])
    : Future[Int] = {
        physicalSwitch.flatMap {
            case None => throw ConfigException("Physical Switch not found")
            case Some(ps: PhysicalSwitch) =>
                val ports = updater(ps)
                val ops = ports.map(updateBindings)
                multiOp(client, dbSchema, ops)(vtepExecutor) map {
                    _.foldLeft(0)(_ + _.getCount)
                }
        }
    }

    private def updateBindings(port: PhysicalPort): Table.OvsdbUpdate = {
        portTable.table.asInstanceOf[PhysicalPortTable]
                       .updateBindings(port)
    }

    private def macFromUpdate(u: VtepEntryUpdate[MacEntry]): VtepMAC = u match {
        case VtepEntryUpdate(null, null) => null
        case VtepEntryUpdate(p, null)  => p.mac
        case VtepEntryUpdate(null, n) => n.mac
        case VtepEntryUpdate(p, n) => if (p.mac != null) p.mac else n.mac
    }

    private def lsIdFromUpdate(u: VtepEntryUpdate[MacEntry]): UUID = u match {
        case VtepEntryUpdate(null, null) => null
        case VtepEntryUpdate(p, null)  => p.logicalSwitchId
        case VtepEntryUpdate(null, n) => n.logicalSwitchId
        case VtepEntryUpdate(p, n) =>
            if (p.logicalSwitchId != null) p.logicalSwitchId
            else n.logicalSwitchId
    }

    private def macUpdateToMacLocations(entry: VtepTableUpdate[_ <: MacEntry],
                                        someTunnelIp: Option[IPv4Addr])
    : Seq[MacLocation] = entry match {
        case VtepEntryUpdate(null, null) => List()
        case VtepEntryUpdate(p, n) => someTunnelIp match {
            case Some(tunnelIp) =>
                val tunnel = if (n == null) null else tunnelIp
                val mac = macFromUpdate(VtepEntryUpdate(p, n))
                val lsId = lsIdFromUpdate(VtepEntryUpdate(p, n))
                val ls = if (lsId == null) None else lsTable.get(lsId)
                val oldIp = if (p == null) null else p.ip
                val newIp = if (n == null) null else n.ip
                val newerIp = if (newIp == null) oldIp else newIp
                var macLocations = List[MacLocation]()

                if (ls.isEmpty)
                    log.info("unknown logical switch {} on vtep " +
                             endPoint, lsId)
                else if (oldIp != null && newIp != null && oldIp != newIp)
                // The ip has changed: generate an additional
                // maclocation to remove the old ip mapping
                    macLocations = List(
                        MacLocation(mac, oldIp, ls.get.name, null),
                        MacLocation(mac, newerIp, ls.get.name, tunnel))
                else
                    macLocations = List(
                        MacLocation(mac, newerIp, ls.get.name, tunnel))

                macLocations
            case None => List()
        }
        case _ => List()
    }

    /** Provides a snapshot of the specified MAC tables. */
    private def currentMac(ucastTable: OvsdbCachedTable[UcastMac],
                           mcastTable: OvsdbCachedTable[McastMac])
    : Future[Seq[MacLocation]] = {
        vxlanTunnelIp map { someTunnelIp =>
            ucastTable.getAll.toSeq.flatMap {
                m => macUpdateToMacLocations(VtepEntryUpdate.addition(m),
                                             someTunnelIp)
            } ++
            mcastTable.getAll.toSeq.flatMap {
                m => macUpdateToMacLocations(VtepEntryUpdate.addition(m),
                                             someTunnelIp)
            }
        }
    }

    /** Returns an observer for the given MAC updater. */
    private def macUpdater(updater: MacUpdater)
    : Future[Observer[MacLocation]] = {
        onReady {
            val pipe = PublishSubject.create[MacLocation]()
            // We use the backpressure buffer to prevent overlapping mac
            // updates, which can lead to order alterations if some initial
            // update fails but the following ones succeed...
            // TODO: we may add a buffer operator with a closingSelector
            // to increase the efficiency, though it may add complexity
            // to error processing (e.g. if the accumulated update is too
            // big, we may run into trouble with ovsdb request max sizes...
            pipe.onBackpressureBuffer(MaxBackpressureBuffer, panicAlert(log))
                .observeOn(vtepScheduler)
                .onBackpressureBuffer(MaxBackpressureBuffer, panicAlert(log))
                .subscribe(updater)
            pipe
        }
    }
}
