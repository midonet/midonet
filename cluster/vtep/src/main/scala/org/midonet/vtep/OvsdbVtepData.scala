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
import java.util.concurrent.Executor

import scala.collection.Iterable
import scala.collection.JavaConversions.asJavaIterable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.slf4j.LoggerFactory
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.{Observable, Observer, Subscriber}

import org.midonet.cluster.data.vtep.{VtepConfigException, VtepData}
import org.midonet.cluster.data.vtep.model._
import org.midonet.packets.IPv4Addr
import org.midonet.util.concurrent.CallingThreadExecutionContext
import org.midonet.util.functors.makeFunc1
import org.midonet.vtep.schema.Table.OvsdbOperation
import org.midonet.vtep.schema._

/**
 * This class handles the data from an ovsdb-compliant vtep
 */
class OvsdbVtepData(val endPoint: VtepEndPoint, val client: OvsdbClient,
                    val dbSchema: DatabaseSchema, val vtepThread: Executor)
    extends VtepData {

    private val log = LoggerFactory.getLogger(classOf[OvsdbVtepData])
    private val vtepContext = ExecutionContext.fromExecutor(vtepThread)
    private val vtepScheduler = Schedulers.from(vtepThread)

    case class ConfigException(msg: String)
        extends VtepConfigException(endPoint, msg)

    private val lsTable =
        new OvsdbCachedTable[LogicalSwitchTable, LogicalSwitch](
            client, new LogicalSwitchTable(dbSchema), classOf[LogicalSwitch],
            vtepThread)
    private val psTable =
        new OvsdbCachedTable[PhysicalSwitchTable, PhysicalSwitch](
            client, new PhysicalSwitchTable(dbSchema), classOf[PhysicalSwitch],
            vtepThread)
    private val portTable =
        new OvsdbCachedTable[PhysicalPortTable, PhysicalPort](
            client, new PhysicalPortTable(dbSchema), classOf[PhysicalPort],
            vtepThread)
    private val locSetTable =
        new OvsdbCachedTable[PhysicalLocatorSetTable, PhysicalLocatorSet](
            client, new PhysicalLocatorSetTable(dbSchema),
            classOf[PhysicalLocatorSet], vtepThread)
    private val locTable =
        new OvsdbCachedTable[PhysicalLocatorTable, PhysicalLocator](
            client, new PhysicalLocatorTable(dbSchema),
            classOf[PhysicalLocator], vtepThread)
    private val uLocalTable =
        new OvsdbCachedTable[UcastMacsLocalTable, UcastMac](
            client, new UcastMacsLocalTable(dbSchema), classOf[UcastMac],
            vtepThread)
    private val uRemoteTable =
        new OvsdbCachedTable[UcastMacsRemoteTable, UcastMac](
            client, new UcastMacsRemoteTable(dbSchema), classOf[UcastMac],
            vtepThread)
    private val mLocalTable =
        new OvsdbCachedTable[McastMacsLocalTable, McastMac](
            client, new McastMacsLocalTable(dbSchema), classOf[McastMac],
            vtepThread)
    private val mRemoteTable =
        new OvsdbCachedTable[McastMacsRemoteTable, McastMac](
            client, new McastMacsRemoteTable(dbSchema), classOf[McastMac],
            vtepThread)

    private val uLocalMonitor =
        new OvsdbTableMonitor[UcastMacsLocalTable, UcastMac](
            client, uLocalTable.table, classOf[UcastMac])
    private val mLocalMonitor =
        new OvsdbTableMonitor[McastMacsLocalTable, McastMac](
            client, mLocalTable.table, classOf[McastMac])

    // TODO: is there a simpler way to combine futures?
    private val ready: Future[Boolean] =
        List(lsTable.ready, psTable.ready, portTable.ready,
             locSetTable.ready, locTable.ready,
             uLocalTable.ready, uRemoteTable.ready,
             mLocalTable.ready, mRemoteTable.ready)
            .foldLeft(Future.successful(true))((a, b) => a.andThen({
            case Success(available) =>
                b.map(_ && available)(CallingThreadExecutionContext)
            case Failure(exc) => Future.failed(exc)
        })(CallingThreadExecutionContext))

    override def vxlanTunnelIp: Option[IPv4Addr] =
        Await.result(getTunnelIp, Duration.Inf)

    override def macLocalUpdates: Observable[MacLocation] =
        Observable.merge(uLocalMonitor.observable, mLocalMonitor.observable)
            .onBackpressureBuffer()
            .observeOn(vtepScheduler)
            .concatMap(macUpdateToMacLocationsFunc)

    override def currentMacLocal: Seq[MacLocation] =
        Await.result(getCurrentMacLocal, Duration.Inf)

    override def macRemoteUpdater: Observer[MacLocation] =
        Await.result(getMacRemoteUpdater, Duration.Inf)

    override def ensureLogicalSwitch(name: String, vni: Int)
    : Try[LogicalSwitch] =
        Try(Await.result(acquireLogicalSwitch(name, vni), Duration.Inf))

    override def ensureBindings(lsName: String,
                                bindings: Iterable[(String, Short)])
    : Try[Unit] =
        Try(Await.result(setBindings(lsName, bindings), Duration.Inf))

    override def removeLogicalSwitch(name: String): Try[Unit] =
        Try(Await.result(deleteLogicalSwitch(name), Duration.Inf))

    private class MacRemoteUpdater extends Subscriber[MacLocation] {
        private val self = this
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
                case u: UcastMac =>
                    ops.add(uRemoteTable.table.insert(u))
                case m: McastMac =>
                    ops.add(mRemoteTable.table.insert(m))
            })
            OvsdbTools.multiOp(client, dbSchema, ops).future.onComplete({
                case Failure(err) =>
                    log.warn("failed to insert entries: " + entries, err)
                    self.request(1)
                case Success(_) =>
                    self.request(1)
            }) (CallingThreadExecutionContext)
        }

        private def applyRemoteMacDeletion(macLocation: MacLocation): Unit = {
            val entries = macLocationToMacEntries(macLocation)
            val ops = new util.ArrayList[OvsdbOperation]()
            entries.foreach({
                case u: UcastMac =>
                    ops.add(uRemoteTable.table.delete(u))
                case m: McastMac =>
                    ops.add(mRemoteTable.table.delete(m))
            })
            OvsdbTools.multiOp(client, dbSchema, ops).future.onComplete({
                case Failure(err) =>
                    log.warn("failed to remove entries: " + entries, err)
                    self.request(1)
                case Success(_) =>
                    self.request(1)
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
            pipe.onBackpressureBuffer()
                .observeOn(vtepScheduler)
                .onBackpressureBuffer()
                .subscribe(new MacRemoteUpdater())
            pipe
        })(vtepContext)

    private def getCurrentMacLocal: Future[Seq[MacLocation]] =
        ready.collect({
            case true =>
                uLocalTable.getAll.values.toSeq.flatMap{
                    m => macUpdateToMacLocations(VtepEntryUpdate.addition(m))} ++
                mLocalTable.getAll.values.toSeq.flatMap{
                    m => macUpdateToMacLocations(VtepEntryUpdate.addition(m))}
        })(vtepContext)

    private def psSwitch: Option[PhysicalSwitch] =
        psTable.getAll.values.find(_.mgmtIps.contains(endPoint.mgmtIp))

    private def lsSwitch(name: String): Option[LogicalSwitch] =
        lsTable.getAll.values.find(_.name == name)

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

    private def macUpdateToMacLocations(entry: VtepTableUpdate[_ <: MacEntry])
        : Seq[MacLocation] = entry match {
            case VtepEntryUpdate(null, null) => List()
            case VtepEntryUpdate(p, n) => getTunnelIp.value match {
                case Some(Success(Some(tunnelIp))) =>
                    val tunnel = if (n == null) null else tunnelIp
                    val mac = macFromUpdate(VtepEntryUpdate(p, n))
                    val lsId = lsIdFromUpdate(VtepEntryUpdate(p, n))
                    val ls = if (lsId == null) None else lsTable.get(lsId)
                    val oldIp = if (p == null) null else p.ip
                    val newIp = if (n == null) null else n.ip
                    val newerIp = if (newIp == null) oldIp else newIp
                    var macLocations = List[MacLocation]()

                    if (ls == None)
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

    private def acquireLogicalSwitch(name: String, vni: Int)
        : Future[LogicalSwitch] =
        ready.collect({
            case true => lsSwitch(name)
        })(vtepContext).flatMap {
            case Some(ls) => Future.successful(ls)
            case None =>
                val ls = LogicalSwitch(name, vni, "")
                lsTable.insert(ls).collect {case id => ls} (vtepContext)
        }(vtepContext)

    private def setBindings(lsName: String, bindings: Iterable[(String, Short)])
        :Future[Unit] = ready.flatMap {case _ =>
            (psSwitch, lsSwitch(lsName)) match {
                case (None, _) =>
                    throw ConfigException("Physical Switch not found")
                case (_, None) =>
                    throw ConfigException("Logical Switch not found: " + lsName)
                case (Some(ps), Some(ls)) =>
                    val boundPorts = bindings.map(_._1).toSet
                    var ports = ps.ports.flatMap(portTable.get)
                        .filter(p => boundPorts.contains(p.name))
                        .map(p => (p.name, p)).toMap
                    for (b <- bindings) ports.get(b._1) match {
                        case None =>
                            log.warn("Physical port {} not found", b._1)
                        case Some(p) =>
                            ports = ports updated
                                    (p.name, p.newBinding(b._2.toInt, ls.uuid))
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

    private def deleteLogicalSwitch(name: String): Future[Unit] =
        ready.flatMap {case _ =>
            lsSwitch(name) match {
                case None =>
                    throw ConfigException("Logical Switch not found: " + name)
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
