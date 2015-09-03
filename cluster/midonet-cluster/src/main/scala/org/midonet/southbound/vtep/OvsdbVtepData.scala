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
import org.midonet.cluster.data.vtep.{VtepConfigException, VtepData, VtepStateException}
import org.midonet.packets.IPv4Addr
import org.midonet.southbound.vtep.schema.Table.OvsdbOperation
import org.midonet.southbound.vtep.schema._
import org.midonet.util.concurrent._
import org.midonet.util.functors.{makeAction0, makeFunc1}
import org.midonet.southbound.vtep.OvsdbOperations._

/**
 * This class handles the data from an OVSDB-compliant VTEP.
 */
class OvsdbVtepData(val endPoint: VtepEndPoint, val client: OvsdbClient,
                    val dbSchema: DatabaseSchema, val vtepExecutor: Executor,
                    val eventExecutor: Executor)
    extends VtepData {
    import OvsdbVtepData._

    private val MaxBackpressureBuffer = 100000

    private val log =
        Logger(LoggerFactory.getLogger(s"org.midonet.vtep.vtep-$endPoint-data"))
    private implicit val vtepContext = ExecutionContext.fromExecutor(vtepExecutor)
    private val vtepScheduler = Schedulers.from(vtepExecutor)

    case class ConfigException(msg: String)
        extends VtepConfigException(endPoint, msg)

    private def cachedTable[T <: Table, E <: VtepEntry]
        (table: T, clazz: Class[E]): OvsdbCachedTable[T, E] =
        new OvsdbCachedTable[T, E](client, table, clazz, vtepExecutor, eventExecutor)

    private val lsTable =
        cachedTable(new LogicalSwitchTable(dbSchema), classOf[LogicalSwitch])
    private val psTable =
        cachedTable(new PhysicalSwitchTable(dbSchema), classOf[PhysicalSwitch])
    private val portTable =
        cachedTable(new PhysicalPortTable(dbSchema), classOf[PhysicalPort])
    private val locSetTable =
        cachedTable(new PhysicalLocatorSetTable(dbSchema),
                    classOf[PhysicalLocatorSet])
    private val locTable =
        cachedTable(new PhysicalLocatorTable(dbSchema), classOf[PhysicalLocator])
    private val uLocalTable =
        cachedTable(new UcastMacsLocalTable(dbSchema), classOf[UcastMac])
    private val uRemoteTable =
        cachedTable(new UcastMacsRemoteTable(dbSchema), classOf[UcastMac])
    private val mLocalTable =
        cachedTable(new McastMacsLocalTable(dbSchema), classOf[McastMac])
    private val mRemoteTable =
        cachedTable(new McastMacsRemoteTable(dbSchema), classOf[McastMac])

    private val uLocalMonitor =
        new OvsdbTableMonitor[UcastMacsLocalTable, UcastMac](
            client, uLocalTable.table, classOf[UcastMac])(eventExecutor)
    private val mLocalMonitor =
        new OvsdbTableMonitor[McastMacsLocalTable, McastMac](
            client, mLocalTable.table, classOf[McastMac])(eventExecutor)

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
        onReady { psTable.getAll.values.toSeq }
    }

    /** Gets the physical switch corresponding to the current VTEP endpoint. */
    override def physicalSwitch: Future[Option[PhysicalSwitch]] = {
        onReady {
            // TODO: We assume the VTEP is configured with a single physical
            // TODO: switch. When using more than one physical swicth, filter by
            // TODO: management IP address using:
            // TODO: psTable.getAll.values.find(_.mgmtIps.contains(endPoint.mgmtIp))
            // TODO: MNA-800
            psTable.getAll.values.headOption
        }
    }

    /** Lists all logical switches. */
    override def logicalSwitches: Future[Seq[LogicalSwitch]] = {
        onReady { lsTable.getAll.values.toSeq }
    }

    /** Gets the logical switch with the specified name. */
    override def logicalSwitch(name: String): Future[Option[LogicalSwitch]] = {
        onReady { lsTable.getAll.values.find(_.name == name) }
    }

    /** Creates a new logical switch with the specified name and VNI. If a
      * logical switch with the same name and VNI already exists, the method
      * succeeds immediately. */
    override def createLogicalSwitch(name: String, vni: Int)
    : Future[LogicalSwitch] = {
        logicalSwitch(name) flatMap {
            case Some(ls) if ls.tunnelKey == vni => Future.successful(ls)
            case Some(ls) =>
                Future.failed(new OvsdbException(
                    client,
                    s"A VTEP with name `$name` already exists but it has" +
                    s"VNI ${ls.tunnelKey}"))
            case None => lsTable.insert(LogicalSwitch(name, vni, ""))
        }
    }

    /** Deletes the logical switch with the specified name, along with all its
      * bindings and MAC entries. */
    override def deleteLogicalSwitch(name: String): Future[Int] = {
        logicalSwitch(name).flatMap {
            case None =>
                throw ConfigException("Logical Switch not found: " + name)
            case Some(ls) =>
                val bindings = portTable.getAll.values
                    .filter(_.isBoundToLogicalSwitchId(ls.uuid))
                    .map(_.clearBindings(ls.uuid))
                val ops = new ArrayBuffer[OvsdbOperation](bindings.size + 5)

                // Clean-up the MAC tables.
                List(uLocalTable, uRemoteTable, mLocalTable, mRemoteTable)
                    .foreach { t =>
                    ops += t.table.deleteByLogicalSwitchId(ls.uuid)
                }

                // Clear the bindings.
                bindings foreach { port =>
                    ops += portTable.table.updateBindings(port)
                }

                // Delete the logical switch.
                ops += lsTable.table.deleteByName(ls.name)

                multiOp(client, dbSchema, ops)(vtepExecutor) map {
                    _.foldLeft(0)(_ + _.getCount)
                }
        }
    }

    /** Lists all physical ports. */
    override def physicalPorts: Future[Seq[PhysicalPort]] = {
        onReady { portTable.getAll.values.toSeq }
    }

    /** Gets the physical port with the specified port identifier. */
    override def physicalPort(portId: UUID): Future[Option[PhysicalPort]] = {
        onReady { portTable.get(portId) }
    }

    /** Adds the bindings for the logical switch with the specified name. The
      * bindings are specified as an [[Iterable]] of port name and VLAN pairs.
      * The methds does not change any existing bindings for the specified
      * physical ports. */
    override def addBindings(lsName: String, bindings: Iterable[(String, Short)])
    : Future[Int] = {
        updateBindings(lsName, (ps, ls) => {
            val boundPorts = bindings.map(_._1).toSet
            var ports = ps.ports.flatMap(portTable.get)
                                .filter(p => boundPorts.contains(p.name))
                                .map(p => (p.name, p)).toMap
            for (binding <- bindings) ports.get(binding._1) match {
                case None =>
                    log.warn("Physical port {} not found", binding._1)
                case Some(p) =>
                    ports = ports updated (p.name, p.addBinding(binding._2.toInt,
                                                                ls.uuid))
            }
            ports.values.toSeq
        })
    }

    /** Sets the bindings for the logical switch with the specified name. The
      * bindings are specified as an [[Iterable]] of port name and VLAN pairs.
      * The method overwrites any of the previous bindings for the specified
      * physical ports, and replaces them with the given ones. The physical
      * ports that are not included in the bindings list are left unchanged.
      * The method returns a future with the number of physical ports that
      * were changed. */
    override def setBindings(lsName: String, bindings: Iterable[(String, Short)])
    : Future[Int] = {
        updateBindings(lsName, (ps, ls) => {
            val boundPorts = bindings.map(_._1).toSet
            var ports = ps.ports.flatMap(portTable.get)
                                .filter(p => boundPorts.contains(p.name))
                                .map(p => (p.name, p.clearBindings())).toMap

            for (binding <- bindings) ports.get(binding._1) match {
                case None =>
                    log.warn("Physical port {} not found", binding._1)
                case Some(p) =>
                    ports = ports updated (p.name, p.addBinding(binding._2.toInt,
                                                                ls.uuid))
            }
            ports.values.toSeq
        })
    }

    /** Clears all bindings for the specified logical switch name. */
    override def clearBindings(lsName: String): Future[Int] = {
        updateBindings(lsName, (ps, ls) => {
            ps.ports.flatMap(portTable.get)
                    .filter(_.isBoundToLogicalSwitchId(ls.uuid))
                    .map(_.clearBindings(ls.uuid))
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

    /** Provides a snapshot of the `Ucast_Mac_Local` and `Mcast_Mac_Local`
      * tables. */
    override def currentMacLocal: Future[Seq[MacLocation]] = {
        vxlanTunnelIp map { someTunnelIp =>
            uLocalTable.getAll.values.toSeq.flatMap {
                m => macUpdateToMacLocations(VtepEntryUpdate.addition(m),
                                             someTunnelIp)
            } ++
            mLocalTable.getAll.values.toSeq.flatMap {
                m => macUpdateToMacLocations(VtepEntryUpdate.addition(m),
                                             someTunnelIp)
            }
        }
    }

    /** Returns an [[Observer]] that will write updates to the remote MACs in
      * the `Ucast_Mac_Remote` or `Mcast_Mac_Remote` tables. */
    override def macRemoteUpdater: Future[Observer[MacLocation]] = {
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
                .subscribe(new MacRemoteUpdater())
            pipe
        }
    }

    private class MacRemoteUpdater extends Subscriber[MacLocation] {
        override def onStart() = request(1)
        override def onCompleted() = {
            log.debug("completed remote MAC location updates")
            this.unsubscribe()
        }
        override def onError(err: Throwable) = {
            log.warn("error on remote MAC location updates", err)
            this.unsubscribe()
        }
        override def onNext(ml: MacLocation) = {
            log.debug("Received remote MAC location: {}", ml)
            if (ml != null && ml.vxlanTunnelEndpoint == null)
                applyRemoteMacDeletion(ml)
            else if (ml != null)
                applyRemoteMacAddition(ml)
        }

        private def applyRemoteMacAddition(macLocation: MacLocation): Unit = {
            macLocationToMacEntries(macLocation) onComplete {
                case Success(entries) =>
                    val ops = new ArrayBuffer[OvsdbOperation](entries.length)
                    entries foreach {
                        case u: UcastMac => ops += uRemoteTable.table.delete(u)
                        case m: McastMac => ops += mRemoteTable.table.delete(m)
                    }
                    multiOp(client, dbSchema, ops)(vtepExecutor) onComplete {
                        case Failure(t) =>
                            log.warn("Failed to insert entries: {}", entries, t)
                            MacRemoteUpdater.this.request(1)
                        case Success(_) =>
                            MacRemoteUpdater.this.request(1)
                    }
                case Failure(t) =>
                    log.warn("Failed to apply remote MAC: {}", macLocation, t)
            }
        }

        private def applyRemoteMacDeletion(macLocation: MacLocation): Unit = {
            macLocationToMacEntries(macLocation) onSuccess { case entries =>
                val ops = new ArrayBuffer[OvsdbOperation](entries.length)
                entries foreach {
                    case u: UcastMac => ops += uRemoteTable.table.delete(u)
                    case m: McastMac => ops += mRemoteTable.table.delete(m)
                }
                multiOp(client, dbSchema, ops)(vtepExecutor) onComplete {
                    case Failure(err) =>
                        log.warn("failed to remove entries: " + entries, err)
                        MacRemoteUpdater.this.request(1)
                    case Success(_) =>
                        MacRemoteUpdater.this.request(1)
                }
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

    private def updateBindings(lsName: String,
                               updater: (PhysicalSwitch, LogicalSwitch) =>
                                   Seq[PhysicalPort])
    : Future[Int] = {
        Future.sequence(Seq(physicalSwitch, logicalSwitch(lsName))).flatMap {
            case Seq(None, _) =>
                throw ConfigException("Physical Switch not found")
            case Seq(_, None) =>
                throw ConfigException("Logical Switch not found: " + lsName)
            case Seq(Some(ps: PhysicalSwitch), Some(ls: LogicalSwitch)) =>
                val ports = updater(ps, ls)
                val ops = ports.map(portTable.table.updateBindings)
                multiOp(client, dbSchema, ops)(vtepExecutor) map {
                    _.foldLeft(0)(_ + _.getCount)
                }
        }
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

    private def macLocationToMacEntries(ml: MacLocation): Future[Seq[MacEntry]] = {
        logicalSwitch(ml.logicalSwitchName).map {
            case None =>
                log.warn("unknown logical switch in MAC location: {}",
                         ml.logicalSwitchName)
                Seq()
            case Some(ls) if ml.vxlanTunnelEndpoint == null =>
                if (ml.mac.isUcast)
                    Seq(UcastMac(ls.uuid, ml.mac, ml.ipAddr, null))
                else
                    Seq(McastMac(ls.uuid, ml.mac, ml.ipAddr, null))
            case Some(ls) if ml.mac.isUcast =>
                locTable.getAll.values
                    .filter(_.dstIp == ml.vxlanTunnelEndpoint)
                    .map(loc => UcastMac(ls.uuid, ml.mac, ml.ipAddr, loc.uuid))
                    .toSeq match {
                    case Nil =>
                        val loc = PhysicalLocator(ml.vxlanTunnelEndpoint)
                        // we will catch locator insertion errors during the
                        // insertion of the entries; this way we don't have
                        // to block and we keep the order of the updates
                        locTable.insert(loc).onFailure { case e: Throwable =>
                            log.warn("failed to insert locator " + loc, e)
                        }
                        Seq(UcastMac(ls.uuid, ml.mac, ml.ipAddr, loc.uuid))
                    case entries => entries
                }
            case Some(ls) =>
                val locatorIds = locTable.getAll.values
                    .filter(_.dstIp == ml.vxlanTunnelEndpoint)
                    .map(_.uuid) match {
                    case Nil =>
                        val loc = PhysicalLocator(ml.vxlanTunnelEndpoint)
                        // we will catch locator insertion errors during the
                        // insertion of the entries; this way we don't have
                        // to block and we keep the order of the updates
                        locTable.insert(loc).onFailure { case e: Throwable =>
                            log.warn("failed to insert locator " + loc, e)
                        }
                        List(loc.uuid)
                    case ids => ids
                }
                locSetTable.getAll.values
                    .filter(s => locatorIds.exists(s.locatorIds.contains))
                    .map(set => McastMac(ls.uuid, ml.mac, ml.ipAddr, set.uuid))
                    .toSeq match {
                    case Nil =>
                        val set = PhysicalLocatorSet(locatorIds.toSet)
                        // we will catch locator insertion errors during the
                        // insertion of the entries; this way we don't have
                        // to block and we keep the order of the updates
                        locSetTable.insert(set)
                        locSetTable.insert(set).onFailure { case e: Throwable =>
                            log.warn("failed to insert locator set " + set, e)
                        }
                        Seq(McastMac(ls.uuid, ml.mac, ml.ipAddr, set.uuid))
                    case entries => entries
                }
        }
    }

}

object OvsdbVtepData {
    def panicAlert(log: Logger) = makeAction0 {
        log.error("OVSDB client buffer overflow. The VxGW service will NOT " +
                  "function correctly, please restart the Cluster server.")
    }
}
