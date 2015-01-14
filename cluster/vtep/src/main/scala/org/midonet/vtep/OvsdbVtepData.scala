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

import java.util
import java.util.UUID
import java.util.concurrent.{Executor, TimeUnit}

import scala.collection.Iterable
import scala.collection.JavaConversions.asJavaIterable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.slf4j.{Logger, LoggerFactory}
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.{Observable, Observer, Subscriber}

import org.midonet.cluster.data.vtep.model._
import org.midonet.cluster.data.vtep.{VtepConfigException, VtepData}
import org.midonet.packets.IPv4Addr
import org.midonet.util.concurrent.CallingThreadExecutionContext
import org.midonet.util.functors
import org.midonet.util.functors.{makeAction0, makeFunc1}
import org.midonet.vtep.schema.Table.OvsdbOperation
import org.midonet.vtep.schema._

/**
 * This class handles the data from an ovsdb-compliant vtep
 */
class OvsdbVtepData(val endPoint: VtepEndPoint, val client: OvsdbClient,
                    val dbSchema: DatabaseSchema, val vtepThread: Executor)
    extends VtepData {
    import OvsdbVtepData._

    val MAX_BACKPRESSURE_BUFFER = 100000

    private val log = LoggerFactory.getLogger(classOf[OvsdbVtepData])
    private val vtepContext = ExecutionContext.fromExecutor(vtepThread)
    private val vtepScheduler = Schedulers.from(vtepThread)

    case class ConfigException(msg: String)
        extends VtepConfigException(endPoint, msg)

    private def cachedTable[T <: Table, E <: VtepEntry]
        (table: T, clazz: Class[E]): OvsdbCachedTable[T, E] =
        new OvsdbCachedTable[T, E](client, table, clazz, vtepThread)

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
            client, uLocalTable.table, classOf[UcastMac])
    private val mLocalMonitor =
        new OvsdbTableMonitor[McastMacsLocalTable, McastMac](
            client, mLocalTable.table, classOf[McastMac])

    private val tables = List(lsTable, psTable, portTable,
                              locSetTable, locTable,
                              uLocalTable, uRemoteTable,
                              mLocalTable, mRemoteTable)

    private val ready: Future[Boolean] =
        Future.reduce[Boolean, Boolean](
            tables.map(_.ready))(_ && _)(CallingThreadExecutionContext)

    override def vxlanTunnelIp: Option[IPv4Addr] =
        Await.result(getTunnelIp, requestTimeout)

    override def macLocalUpdates: Observable[MacLocation] =
        Observable.merge(uLocalMonitor.observable, mLocalMonitor.observable)
            .onBackpressureBuffer(MAX_BACKPRESSURE_BUFFER, panicAlert(log))
            .observeOn(vtepScheduler)
            .concatMap(macUpdateToMacLocationsFunc)

    override def currentMacLocal: Seq[MacLocation] =
        Await.result(getCurrentMacLocal, requestTimeout)

    override def currentMacLocal(nwId: UUID): Seq[MacLocation] =
        Await.result(getCurrentMacLocal(nwId), Duration.Inf)

    override def macRemoteUpdater: Observer[MacLocation] =
        Await.result(getMacRemoteUpdater, requestTimeout)

    override def ensureLogicalSwitch(networkId: UUID, vni: Int)
    : Try[LogicalSwitch] =
        Try(Await.result(acquireLogicalSwitch(networkId, vni), Duration.Inf))

    override def ensureBindings(networkId: UUID,
                                bindings: Iterable[VtepBinding])
    : Try[Unit] =
        Try(Await.result(setBindings(networkId, bindings), Duration.Inf))

    override def removeBinding(portName: String, vlanId: Short): Try[Unit] =
        Try(Await.result(rmBinding(portName, vlanId), Duration.Inf))

    override def createBinding(portName: String, vlanId: Short, networkId: UUID)
    : Try[Unit] =
        Try(Await.result(mkBinding(portName, vlanId, networkId), Duration.Inf))

    override def removeLogicalSwitch(networkId: UUID): Try[Unit] =
        Try(Await.result(deleteLogicalSwitch(networkId), Duration.Inf))

    override def listLogicalSwitches: Set[LogicalSwitch] =
        Await.result(getCurrentLogicalSwitches, Duration.Inf)

    override def listPhysicalSwitches: Set[PhysicalSwitch] =
        Await.result(getCurrentPhysicalSwitches, Duration.Inf)

    override def physicalPorts(psId: UUID): Set[PhysicalPort] =
        Await.result(getPhysicalPorts(psId), Duration.Inf)

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
            val entries = macLocationToMacEntries(macLocation)
            val ops = new util.ArrayList[OvsdbOperation]()
            entries.foreach({
                case u: UcastMac => ops.add(uRemoteTable.table.insert(u))
                case m: McastMac => ops.add(mRemoteTable.table.insert(m))
            })
            OvsdbTools.multiOp(client, dbSchema, ops).future.onComplete({
                case Failure(err) =>
                    log.warn("failed to insert entries: " + entries, err)
                    MacRemoteUpdater.this.request(1)
                case Success(_) =>
                    MacRemoteUpdater.this.request(1)
            }) (CallingThreadExecutionContext)
        }

        private def applyRemoteMacDeletion(macLocation: MacLocation): Unit = {
            val entries = macLocationToMacEntries(macLocation)
            val ops = new util.ArrayList[OvsdbOperation]()
            entries.foreach({
                case u: UcastMac if u.ipAddr == null =>
                    ops.add(uRemoteTable.table.deleteByMac(u.macAddr,
                                                           u.logicalSwitchId))
                case u: UcastMac =>
                    ops.add(uRemoteTable.table.delete(u))
                case m: McastMac =>
                    ops.add(mRemoteTable.table.deleteByMac(m.macAddr,
                                                           m.logicalSwitchId))
            })
            OvsdbTools.multiOp(client, dbSchema, ops).future.onComplete({
                case Failure(err) =>
                    log.warn("failed to remove entries: " + entries, err)
                    MacRemoteUpdater.this.request(1)
                case Success(_) =>
                    MacRemoteUpdater.this.request(1)
            }) (CallingThreadExecutionContext)
        }
    }

    private def getMacRemoteUpdater: Future[Observer[MacLocation]] =
        ready.collect({case true =>
            val pipe = PublishSubject.create[MacLocation]()
            // We use the backpressure buffer to prevent overlapping mac
            // updates, which can lead to order alterations if some initial
            // update fails but the following ones succeed...
            // TODO: we may add a buffer operator with a closingSelector
            // to increase the efficiency, though it may add complexity
            // to error processing (e.g. if the accumulated update is too
            // big, we may run into trouble with ovsdb request max sizes...
            pipe.onBackpressureBuffer(MAX_BACKPRESSURE_BUFFER, panicAlert(log))
                .observeOn(vtepScheduler)
                .onBackpressureBuffer(MAX_BACKPRESSURE_BUFFER, panicAlert(log))
                .subscribe(new MacRemoteUpdater())
            pipe
        })(vtepContext)

    private def getCurrentMacLocal: Future[Seq[MacLocation]] =
        ready.collect({
            case true =>
                uLocalTable.getAll.values.toSeq.flatMap {
                    m => macUpdateToMacLocations(VtepEntryUpdate.addition(m))
                } ++
                mLocalTable.getAll.values.toSeq.flatMap {
                    m => macUpdateToMacLocations(VtepEntryUpdate.addition(m))
                }
        })(vtepContext)

    private def getCurrentMacLocal(nwId: UUID): Future[Seq[MacLocation]] =
        ready.collect({
            case true =>
                val id = lsSwitch(nwId).map(_.uuid).orNull
                uLocalTable.getAll.values.filter(_.logicalSwitchId == id).toSeq
                .flatMap {
                    m => macUpdateToMacLocations(VtepEntryUpdate.addition(m))
                } ++
                mLocalTable.getAll.values.filter(_.logicalSwitchId == id).toSeq
                .flatMap {
                    m => macUpdateToMacLocations(VtepEntryUpdate.addition(m))
                }
        })(vtepContext)

    private def getCurrentLogicalSwitches: Future[Set[LogicalSwitch]] =
        ready.collect({
            case true => lsTable.getAll.values.toSet
        })(vtepContext)

    private def getCurrentPhysicalSwitches: Future[Set[PhysicalSwitch]] =
        ready.collect({
            case true => psTable.getAll.values.toSet
        })(vtepContext)

    private def getPhysicalPorts(psId: UUID): Future[Set[PhysicalPort]] =
        ready.collect({
            case true => psTable.get(psId).map(_.ports.flatMap(portTable.get))
                                          .getOrElse(Set.empty)
        })(vtepContext)

    private def psSwitch: Option[PhysicalSwitch] =
        psTable.getAll.values.find(_.mgmtIps.contains(endPoint.mgmtIp))

    private def lsSwitch(name: String): Option[LogicalSwitch] =
        lsTable.getAll.values.find(_.name == name)

    private def lsSwitch(nwId: UUID): Option[LogicalSwitch] =
        lsTable.getAll.values.find(_.networkId == nwId)

    private def getTunnelIp: Future[Option[IPv4Addr]] =
        ready.collect{case true => psSwitch.flatMap(_.tunnelIp)}(vtepContext)

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

    private val macUpdateToMacLocationsFunc =
        makeFunc1[VtepTableUpdate[_ <: MacEntry], Observable[MacLocation]] {
            entry => Observable.from(macUpdateToMacLocations(entry))
        }

    /**
     * Converts a Row update notification from the OVSDB client to a stream of
     * MacLocation updates that can be applied to a VxGW Peer. A change
     * in a given row is interpreted as follows:
     *
     * - Addition: when r.getOld is null and r.getNew isn't. The MAC
     *   contained in r.getNew is now located at the ucast_local table
     *   of the monitored vtep. In this case, the resulting MacLocation
     *   will contain the new MAC, plus the vxlan tunnel endpoint IP of the
     *   VTEP being monitored by this VtepBroker.
     * - Deletion: when r.getOld is not null, and r.getNew is null. The MAC
     *   contained in the r.getOld ceased to be located at the VTEP
     *   we're now monitoring, so the resulting MacLocation will contain
     *   the MAC and a null vxlan tunnel endpoint IP.
     * - Update: when both the r.getOld and r.getNew values are not
     *   null. In the new row, only fields that changed would be populated. An
     *   update would happen for several reasons:
     *   - The MAC changes: ignored, because MN doesn't update the mac
     *     so this means an operator wrongly manipulated the VTEP's database.
     *   - The IP changes: only relevant for ARP supression. In this case we
     *     have to add the mac's ip to the MacLocation and will have to update
     *     it accordingly.
     *   - The logical switch changes: again, MN will never trigger this
     *     change so it will be ignored.
     *   - The locator changed: this refers to the local tunnel IP,
     *     which being local should remain the same.
     *
     *   @return a sequence containing the corresponding MacLocation
     *           instances, empty if the logical switch doesn't exist (e.g.
     *           because it is deleted during the call)
     */
    private def macUpdateToMacLocations(entry: VtepTableUpdate[_ <: MacEntry])
        : Seq[MacLocation] = entry match {
            case VtepEntryUpdate(null, null) => List()
            case VtepEntryUpdate(p, n) => psSwitch.flatMap(_.tunnelIp) match {
                case Some(tunnelIp) =>
                    val tunnel = if (n == null) null else tunnelIp
                    val mac = macFromUpdate(VtepEntryUpdate(p, n))
                    val lsId = lsIdFromUpdate(VtepEntryUpdate(p, n))
                    val ls = if (lsId == null) None else lsTable.get(lsId)
                    val oldIp = if (p == null) null else p.ip
                    val newIp = if (n == null) null else n.ip
                    val newerIp = if (newIp == null) oldIp else newIp
                    val macLocations = if (ls.isEmpty) {
                        log.info("unknown logical switch {} on vtep " +
                                 endPoint, lsId)
                        List[MacLocation]()
                    } else if (oldIp != null && newIp != null
                               && oldIp != newIp) {
                        // The ip has changed: generate an additional
                        // maclocation to remove the old ip mapping
                        List(
                            MacLocation(mac, oldIp, ls.get.name, null),
                            MacLocation(mac, newerIp, ls.get.name, tunnel))
                    } else {
                        List(
                            MacLocation(mac, newerIp, ls.get.name, tunnel)
                        )
                    }
                    macLocations
                case _ => List()
            }
            case _ => List()
        }

    private def macLocationToMacEntries(ml: MacLocation): Seq[MacEntry] = {
        lsSwitch(ml.logicalSwitchName) match {
            case None =>
                log.warn("unknown logical switch in MAC location: {}",
                         ml.logicalSwitchName)
                Seq()
            case Some(ls) if ml.vxlanTunnelEndpoint == null =>
                if (ml.mac.isUcast) {
                    Seq(UcastMac(ls.uuid, ml.mac, ml.ipAddr, null))
                } else {
                    Seq(McastMac(ls.uuid, ml.mac, ml.ipAddr, null))
                }
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
                        locTable.insert(loc).onFailure {case e: Throwable =>
                            log.warn("failed to insert locator " + loc, e)
                        } (CallingThreadExecutionContext)
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
                        locTable.insert(loc).onFailure {case e: Throwable =>
                            log.warn("failed to insert locator " + loc, e)
                        } (CallingThreadExecutionContext)
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
                        locSetTable.insert(set).onFailure {case e: Throwable =>
                            log.warn("failed to insert locator set " + set, e)
                        } (CallingThreadExecutionContext)
                        Seq(McastMac(ls.uuid, ml.mac, ml.ipAddr, set.uuid))
                    case entries => entries
                }
        }
    }

    private def acquireLogicalSwitch(networkId: UUID, vni: Int)
        : Future[LogicalSwitch] =
        ready.collect({
            case true => lsSwitch(networkId)
        })(vtepContext).flatMap {
            case Some(ls) if ls.tunnelKey == vni => Future.successful(ls)
            case Some(ls) =>
                val updated =
                    new LogicalSwitch(ls.uuid, ls.name, vni, ls.description)
                lsTable.insert(updated).collect {case id => updated} (vtepContext)
            case None =>
                val ls = LogicalSwitch(networkId, vni, "")
                lsTable.insert(ls).collect {case id => ls} (vtepContext)
        }(vtepContext)

    private def setBindings(networkId: UUID, bindings: Iterable[VtepBinding])
        :Future[Unit] = ready.flatMap {case _ =>
            (psSwitch, lsSwitch(networkId)) match {
                case (None, _) =>
                    throw ConfigException("Physical Switch not found")
                case (_, None) =>
                    throw ConfigException("Logical Switch not found: " + networkId)
                case (Some(ps), Some(ls)) =>
                    val boundPorts = bindings.map(_.portName).toSet
                    var ports = ps.ports.flatMap(portTable.get)
                        .filter(p => boundPorts.contains(p.name))
                        .map(p => (p.name, p)).toMap
                    for (b <- bindings) ports.get(b.portName) match {
                        case None =>
                            log.warn("Physical port {} not found", b.portName)
                        case Some(p) =>
                            ports = ports updated
                                    (p.name, p.newBinding(b.vlanId.toInt, ls.uuid))
                    }
                    val ops = new util.ArrayList[OvsdbOperation]()
                    val hint = UUID.randomUUID()
                    ports.values.foreach(p => {
                        ops.add(portTable.table.updateBindings(p))
                        portTable.insertHint(p, hint)
                    })
                    val status = OvsdbTools.multiOp(client, dbSchema, ops).future
                    status.onFailure {case e: Throwable =>
                        ports.values.foreach(
                            p => portTable.removeHint(p.uuid, hint))
                    } (vtepContext)
                    status.map(r => {})(CallingThreadExecutionContext)
            }
        }(vtepContext)

    private def mkBinding(portName: String, vlanId: Short, nwId: UUID)
    :Future[Unit] = ready.flatMap {case _ =>
        (psSwitch, lsSwitch(nwId)) match {
            case (None, _) =>
                throw ConfigException("Physical Switch not found")
            case (_, None) =>
                throw ConfigException("Logical Switch not found: " + nwId)
            case (Some(ps), Some(ls)) =>
                val port = ps.ports.flatMap(portTable.get)
                    .find(_.name == portName)
                    .getOrElse(throw ConfigException("Port not found: " + portName))
                val updated = port.newBinding(vlanId.toInt, ls.uuid)
                val hint = UUID.randomUUID()
                portTable.insertHint(updated, hint)
                val status = OvsdbTools.singleOp(
                    client, dbSchema, portTable.table.updateBindings(updated))
                    .future
                status.onFailure {case e: Throwable =>
                    portTable.removeHint(updated.uuid, hint)
                } (vtepContext)
                status.map(r => {})(CallingThreadExecutionContext)
        }
    }(vtepContext)

    private def rmBinding(portName: String, vlanId: Short): Future[Unit] =
        ready.flatMap {case _ => psSwitch match {
            case None =>
                throw ConfigException("Physical Switch not found")
            case Some(ps) =>
                val port = ps.ports.flatMap(portTable.get)
                    .find(_.name == portName)
                    .getOrElse(throw ConfigException(
                    "Port not found: " + portName))
                val ls = port.vlanBindings.get(vlanId.toInt)
                    .flatMap(lsTable.get)
                    .getOrElse(throw ConfigException(
                    "Binding not found: " + portName + " " + vlanId))

                val ops = new util.ArrayList[OvsdbOperation]()
                val updated = port.clearBinding(vlanId.toInt)
                ops.add(portTable.table.updateBindings(updated))

                val curBindings = portTable.getAll.values.flatMap(
                    _.vlanBindings.filter(_._2 == ls.uuid))
                if (curBindings.size <= 1) {
                    // last binding, and its a mn switch: remove it
                    ops.add(lsTable.table.deleteByName(ls.name))
                }
                OvsdbTools.multiOp(client, dbSchema, ops).future
                    .map(r => {})(CallingThreadExecutionContext)
        }} (vtepContext)

    private def deleteLogicalSwitch(networkId: UUID): Future[Unit] =
        ready.flatMap {case _ =>
            lsSwitch(networkId) match {
                case None =>
                    throw ConfigException("Logical Switch not found: " + networkId)
                case Some(ls) =>
                    val ops = new util.ArrayList[OvsdbOperation]()

                    // clean-up mac tables
                    List(uLocalTable, uRemoteTable, mLocalTable, mRemoteTable)
                        .foreach(t => {
                        ops.add(t.table.deleteByLogicalSwitchId(ls.uuid))
                    })

                    // clear bindings
                    portTable.getAll.values
                        .filter(_.isBoundToLogicalSwitchId(ls.uuid))
                        .map(_.clearBindings(ls.uuid))
                        .foreach(p => ops.add(portTable.table.updateBindings(p)))

                    // delete the switch
                    ops.add(lsTable.table.deleteByName(ls.name))

                    OvsdbTools.multiOp(client, dbSchema, ops).future
                        .map(r => {})(CallingThreadExecutionContext)
            }
        } (vtepContext)
}

object OvsdbVtepData {
    final val requestTimeout: Duration = Duration(30, TimeUnit.SECONDS)
    def panicAlert(log: Logger) = makeAction0 {
        log.error("OVSDB client buffer overflow.  The VxGW service will NOT " +
                  "function correctly, please restart the Cluster server.")
    }
}
