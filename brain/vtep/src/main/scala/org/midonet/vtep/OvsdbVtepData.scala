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

import java.util.concurrent.Executor
import java.util.UUID

import scala.collection.JavaConversions.asJavaIterable
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.slf4j.LoggerFactory
import rx.schedulers.Schedulers
import rx.subjects.{PublishSubject, Subject}
import rx.{Subscriber, Observer, Observable}

import org.midonet.cluster.data.vtep.VtepData
import org.midonet.cluster.data.vtep.model._
import org.midonet.packets.IPv4Addr
import org.midonet.util.concurrent.CallingThreadExecutionContext
import org.midonet.util.functors.makeFunc1
import org.midonet.vtep.schema._

/**
 * This class handles the data from an ovsdb-compliant vtep
 */
class OvsdbVtepData(val endPoint: VtepEndPoint, val client: OvsdbClient,
                    val dbSchema: DatabaseSchema, val vtepThread: Executor)
    extends VtepData {

    private val log = LoggerFactory.getLogger(classOf[OvsdbVtepLegacyData])
    private val vtepContext = ExecutionContext.fromExecutor(vtepThread)
    private val vtepScheduler = Schedulers.from(vtepThread)

    private val lsTable =
        new OvsdbCachedTable[LogicalSwitchTable, LogicalSwitch](
            client, new LogicalSwitchTable(dbSchema))
    private val psTable =
        new OvsdbCachedTable[PhysicalSwitchTable, PhysicalSwitch](
            client, new PhysicalSwitchTable(dbSchema))
    private val locSetTable =
        new OvsdbCachedTable[PhysicalLocatorSetTable, PhysicalLocatorSet](
            client, new PhysicalLocatorSetTable(dbSchema))
    private val locTable =
        new OvsdbCachedTable[PhysicalLocatorTable, PhysicalLocator](
            client, new PhysicalLocatorTable(dbSchema))
    private val uLocalTable =
        new OvsdbCachedTable[UcastMacsLocalTable, UcastMac](
            client, new UcastMacsLocalTable(dbSchema))
    private val uRemoteTable =
        new OvsdbCachedTable[UcastMacsRemoteTable, UcastMac](
            client, new UcastMacsRemoteTable(dbSchema))
    private val mLocalTable =
        new OvsdbCachedTable[McastMacsLocalTable, McastMac](
            client, new McastMacsLocalTable(dbSchema))
    private val mRemoteTable =
        new OvsdbCachedTable[McastMacsRemoteTable, McastMac](
            client, new McastMacsRemoteTable(dbSchema))

    private val uLocalMonitor =
        new OvsdbTableMonitor[UcastMacsLocalTable, UcastMac](
            client, uLocalTable.table)
    private val mLocalMonitor =
        new OvsdbTableMonitor[McastMacsLocalTable, McastMac](
            client, mLocalTable.table)

    // TODO: is there a simpler way to combine futures?
    private val ready: Future[Boolean] =
        List(lsTable.ready, psTable.ready, locSetTable.ready, locTable.ready,
             uLocalTable.ready, uRemoteTable.ready,
             mLocalTable.ready, mRemoteTable.ready)
            .foldLeft(Future.successful(true))((a, b) => a.andThen({
            case Success(available) => b.map(_ && available)
            case Failure(exc) => Future.failed(exc)
        })(CallingThreadExecutionContext))

    override def vxlanTunnelIp: Option[IPv4Addr] =
        Await.result(getTunnelIp, Duration.Inf)

    override def macLocalUpdates: Observable[MacLocation] =
        Observable.merge(uLocalMonitor.observable, mLocalMonitor.observable)
            .observeOn(vtepScheduler)
            .concatMap(macUpdateToMacLocationsFunc)

    override def currentMacLocal: Seq[MacLocation] =
        Await.result(getCurrentMacLocal, Duration.Inf)

    override def macRemoteUpdater: Observer[MacLocation] =
        Await.result(getMacRemoteUpdater, Duration.Inf)

    private def getMacRemoteUpdater: Future[Observer[MacLocation]] =
        ready.collect({case true =>
            val pipe = PublishSubject.create[MacLocation]()
            pipe.observeOn(vtepScheduler).subscribe(
                new Subscriber[MacLocation]() {
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
                        if (ml.vxlanTunnelEndpoint == null)
                            applyRemoteMacDeletion(ml)
                        else
                            applyRemoteMacAddition(ml)
                    }
                })
            pipe
        })(vtepContext)

    private def applyRemoteMacAddition(macLocation: MacLocation) = ???
    private def applyRemoteMacDeletion(macLocation: MacLocation) = ???

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
        })

    private def macLocationToMacEntry(ml: MacLocation): Seq[MacEntry] = {
        lsTable.getAll.values.find(_.name == ml.logicalSwitchName) match {
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
                    .toSeq
            case Some(ls) =>
                val locatorIds = locTable.getAll.values
                    .filter(_.dstIp == ml.vxlanTunnelEndpoint)
                    .map(_.uuid)
                locSetTable.getAll.values
                    .filter(s => locatorIds.exists(s.locatorIds.contains))
                    .map(set => McastMac(ls.uuid, ml.mac, ml.ipAddr, set.uuid))
                    .toSeq
        }
    }


    override def ensureLogicalSwitch(name: String,
                                     vni: Int): Try[LogicalSwitch] = ???

    override def removeLogicalSwitch(name: String): Try[Unit] = ???

    override def ensureBindings(lsName: String,
                                bindings: Iterable[(String, Short)]): Try[Unit] = ???

    def getLogicalSwitches: JavaSet[LogicalSwitch] =
        Await.result(ovsdbData.get(), Duration.Inf)
            .getLogicalSwitches.result()

    def getPhysicalLocators: JavaSet[PhysicalLocator] =
        Await.result(ovsdbData.get(), Duration.Inf)
            .getPhysicalLocators.result()

    def getPhysicalLocatorSets: JavaMap[UUID, java.util.Set[UUID]] =
        Await.result(ovsdbData.get(), Duration.Inf)
            .getPhysicalLocatorSets.result()

    def getLocalUcastMacs: JavaSet[MacLocation] =
        Await.result(ovsdbData.get(), Duration.Inf)
            .getUcastMacLocalEntries.result()

    def getRemoteUcastMacs: JavaSet[MacLocation] =
        Await.result(ovsdbData.get(), Duration.Inf)
            .getUcastMacRemoteEntries.result()

}

