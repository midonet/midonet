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

package org.midonet.cluster.services.rest_api.neutron.plugin

import java.util
import java.util.UUID

import javax.ws.rs.WebApplicationException

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.fromExecutor
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import com.google.common.util.concurrent.MoreExecutors
import com.google.inject.Inject
import com.google.protobuf.Message

import org.slf4j.LoggerFactory

import org.midonet.cluster.RestApiNeutronLog
import org.midonet.cluster.data.ZoomConvert.fromProto
import org.midonet.cluster.data.ZoomMetadata.ZoomOwner
import org.midonet.cluster.data.storage.{NotFoundException, ObjectExistsException, _}
import org.midonet.cluster.data.{ZoomClass, ZoomConvert, ZoomObject}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.rest_api._
import org.midonet.cluster.rest_api.neutron.models._
import org.midonet.cluster.services.c3po.NeutronTranslatorManager
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, Delete, Update}
import org.midonet.cluster.services.c3po.translators.TranslationException
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.concurrent.toFutureOps

class NeutronZoomPlugin @Inject()(resourceContext: ResourceContext,
                                  translatorManager: NeutronTranslatorManager)
    extends L3Api
            with GatewayDeviceApi
            with FirewallApi
            with L2GatewayConnectionApi
            with LoadBalancerApi
            with LoadBalancerV2Api
            with NetworkApi
            with SecurityGroupApi
            with VpnServiceApi
            with BgpApi
            with TapAsAServiceApi
            with FirewallLoggingApi {

    private val log = LoggerFactory.getLogger(RestApiNeutronLog)

    private implicit val ec = fromExecutor(MoreExecutors.directExecutor())

    private val timeout = resourceContext.config.requestTimeoutMs millis
    private val store = resourceContext.backend.store

    private def tryRead[T](f: => T): T = tryStorageOp(f)

    private def tryWrite(f: (Transaction) => Unit): Unit = {
        tryStorageOp(store.tryTransaction(ZoomOwner.ClusterNeutron)(f))
    }

    /** Transform StorageExceptions to appropriate HTTP exceptions. */
    private def tryStorageOp[T](f: => T): T = {
        try f catch {
            case e: StorageException =>
                log.debug("Neutron storage operation failed", e)
                throw toHttpException(e)
            case e: TranslationException =>
                log.debug("Neutron storage operation failed", e)
                throw toHttpException(e)
        }
    }

    private def toHttpException(ex: TranslationException)
    : WebApplicationException = {
        ex.cause match {
            case e: StorageException => toHttpException(e)
            case e: IllegalArgumentException =>
                new BadRequestHttpException(ex, ex.getMessage)
            case e: IllegalStateException =>
                new ConflictHttpException(ex, ex.getMessage)
            case _ =>
                new InternalServerErrorHttpException(ex, ex.getMessage)
        }
    }

    private def toHttpException(ex: StorageException)
    : WebApplicationException = {
        ex match {
            case e: NotFoundException =>
                new NotFoundHttpException(e, e.getMessage)
            case e: ObjectExistsException =>
                new ConflictHttpException(e, e.getMessage)
            case e: ReferenceConflictException =>
                new ConflictHttpException(e, e.getMessage)
            case e: ObjectReferencedException =>
                new ConflictHttpException(e, e.getMessage)
            case e: ObjectNameNotUniqueException =>
                new ConflictHttpException(e, e.getMessage)
            case e: StorageException =>
                new InternalServerErrorHttpException(e, e.getMessage)
        }
    }

    def create[T >: Null <: ZoomObject](dto: T)(implicit ct: ClassTag[T]): T = {
        log.debug(s"Create: $dto")
        val protoClass = protoClassOf(dto)
        val neutronOp = Create(toProto(dto, protoClass))
        val id = idOf(neutronOp.model)
        tryWrite { translatorManager.translate(_, neutronOp) }
        log.debug(s"Create ${dto.getClass.getSimpleName} $id succeeded.")
        dto
    }

    def bulkCreate[T >: Null <: ZoomObject](dtos: util.List[T])
                                           (implicit ct: ClassTag[T])
    : util.List[T] = {
        log.debug(s"Bulk create: " + dtos)
        if (dtos.isEmpty) {
            return List.empty[T]
        }

        val dtoClass = dtos.head.getClass
        val protoClass = protoClassOf(dtoClass)
        val creates = dtos.map { d => Create(toProto(d, protoClass)) }
        tryWrite { tx =>
            for (create <- creates) {
                translatorManager.translate(tx, create)
            }
        }
        val ids = list[T](creates.map(c => idOf(c.model)))
        log.debug(s"Bulk create ${dtoClass.getSimpleName} succeeded: $ids")
        ids
    }

    def get[T >: Null <: ZoomObject](id: UUID)(implicit ct: ClassTag[T]): T = {
        val dtoClass = ct.runtimeClass.asInstanceOf[Class[T]]
        log.debug(s"Get ${dtoClass.getSimpleName}: $id")
        val proto = tryRead(
            store.get(protoClassOf(dtoClass), id).await(timeout))
        val dto = fromProto(proto, dtoClass)
        log.debug("Get succeeded: " + dto)
        dto
    }


    def update[T >: Null <: ZoomObject](dto: T)(implicit ct: ClassTag[T]): T = {
        log.debug("Update: " + dto)
        val protoClass = protoClassOf(dto)
        val neutronOp = Update(toProto(dto, protoClass))
        val id = idOf(neutronOp.model)
        tryWrite { translatorManager.translate(_, neutronOp) }
        log.debug(s"Update ${dto.getClass.getSimpleName} $id succeeded")
        dto
    }

    def delete[T >: Null <: ZoomObject](id: UUID, dtoClass: Class[T]): Unit = {
        log.debug(s"Delete ${dtoClass.getSimpleName}: $id")
        val protoClass = protoClassOf(dtoClass)
        val neutronOp = Delete(protoClass, UUIDUtil.toProto(id))
        tryWrite { translatorManager.translate(_, neutronOp) }
        log.debug(s"Delete ${dtoClass.getSimpleName} $id succeeded")
    }

    def list[T >: Null <: ZoomObject](ids: util.List[UUID])
                                    (implicit ct: ClassTag[T]): util.List[T] = {
        val dtoClass = ct.runtimeClass.asInstanceOf[Class[T]]
        val protoClass = protoClassOf(dtoClass)

        log.debug(s"List ${dtoClass.getSimpleName}: $ids")

        val dtos = tryRead {
            store.getAll(protoClass, ids).await(timeout)
                 .map(fromProto(_, dtoClass)).toList
        }

        log.debug(s"List ${dtoClass.getSimpleName} succeeded: $ids")
        dtos
    }

    def listAll[T >: Null <: ZoomObject](dtoClass: Class[T]): util.List[T] = {
        val protoClass = protoClassOf(dtoClass)
        log.debug(s"List all ${dtoClass.getSimpleName}")

        val dtos = tryRead {
            store.getAll(protoClass).await(timeout)
                 .map(fromProto(_, dtoClass))
                 .toList
        }

        log.debug(s"List all ${dtoClass.getSimpleName} succeeded")
        dtos
    }

    @inline
    private def idOf(proto: Message): UUID = {
        val idFieldDesc = proto.getDescriptorForType.findFieldByName("id")
        UUIDUtil.fromProto(proto.getField(idFieldDesc).asInstanceOf[Commons.UUID])
    }

    @inline
    private def protoClassOf(dto: ZoomObject): Class[_ <: Message] = {
        protoClassOf(dto.getClass)
    }

    @inline
    private def protoClassOf(dtoClass: Class[_ <: ZoomObject])
    : Class[_ <: Message] = {
        dtoClass.getAnnotation(classOf[ZoomClass]).clazz
    }

    private def toProto[T <: Message](dto: ZoomObject,
                                      protoClass: Class[T]): T = {
        try ZoomConvert.toProto(dto, protoClass) catch {
            case NonFatal(ex) =>
                throw new BadRequestHttpException(ex, ex.getMessage)
        }
    }

    override def createRouter(dto: Router): Router = create(dto)

    override def addRouterInterface(routerId: UUID, ri: RouterInterface)
    : RouterInterface = create[RouterInterface](ri)

    // When neutron router interface port contains several
    // subnets, only some of them might be deleted from router interface with:
    //    router-interface-delete <router_id> subnet=<subnet_id>
    // If this is the case, we should just update the port without removing it.
    // If whole port is deleted this call does nothing since all the work is done
    // by port interface translator.
    override def removeRouterInterface(routerId: UUID, ri: RouterInterface)
    : RouterInterface = update[RouterInterface](ri)

    override def updateFloatingIp(id: UUID,
                                  floatingIp: FloatingIp): FloatingIp = {
        floatingIp.id = id
        update(floatingIp)
    }

    override def getFloatingIp(id: UUID): FloatingIp = get[FloatingIp](id)

    override def getRouters: util.List[Router] = listAll(classOf[Router])

    override def createFloatingIp(dto: FloatingIp): FloatingIp = create(dto)

    override def deleteFloatingIp(id: UUID)
    : Unit = delete(id, classOf[FloatingIp])

    override def deleteRouter(id: UUID)
    : Unit = delete(id, classOf[Router])

    override def updateRouter(id: UUID, router: Router): Router = {
        router.id = id
        update(router)
    }

    override def getRouter(id: UUID): Router = get[Router](id)

    override def getFloatingIps
    : util.List[FloatingIp] = listAll(classOf[FloatingIp])

    override def getMembers: util.List[Member] = listAll(classOf[Member])

    override def deletePool(id: UUID): Unit = delete(id, classOf[Pool])

    override def getHealthMonitors
    : util.List[HealthMonitor] = listAll(classOf[HealthMonitor])

    override def createVip(dto: VIP): Unit = create(dto)

    override def getMember(id: UUID): Member = get[Member](id)

    override def getPool(id: UUID): Pool = get[Pool](id)

    override def deleteMember(id: UUID): Unit = delete(id, classOf[Member])

    override def updateVip(id: UUID, vip: VIP): Unit = {
        vip.id = id
        update(vip)
    }

    override def updateMember(id: UUID, member: Member): Unit = {
        member.id = id
        update(member)
    }

    override def createPool(dto: Pool): Unit = create(dto)

    override def getVip(id: UUID): VIP = get[VIP](id)

    override def createMember(dto: Member): Unit = create(dto)

    override def deleteVip(id: UUID): Unit = delete(id, classOf[VIP])

    override def updateHealthMonitor(id: UUID, hm: HealthMonitor): Unit = {
        hm.id = id
        update(hm)
    }

    override def deleteHealthMonitor(id: UUID): Unit = {
        delete(id, classOf[HealthMonitor])
    }

    override def updatePool(id: UUID, pool: Pool): Unit = {
        pool.id = id
        update(pool)
    }

    override def getVips: util.List[VIP] = listAll(classOf[VIP])

    override def getPools: util.List[Pool] = listAll(classOf[Pool])

    override def createHealthMonitor(dto: HealthMonitor): Unit = create(dto)

    override def getHealthMonitor(id: UUID)
    : HealthMonitor = get[HealthMonitor](id)

    override def updatePort(id: UUID, port: Port): Port = {
        port.id = id
        update(port)
    }

    // LBaaS V2
    override def getLoadBalancerV2(id: UUID): LoadBalancerV2 = get[LoadBalancerV2](id)

    override def getLoadBalancersV2: util.List[LoadBalancerV2] = listAll(classOf[LoadBalancerV2])

    override def createLoadBalancerV2(lbv2: LoadBalancerV2): LoadBalancerV2 = create(lbv2)

    override def updateLoadBalancerV2(id: UUID, lbv2: LoadBalancerV2): LoadBalancerV2 = {
        lbv2.id = id
        update(lbv2)
    }

    override def deleteLoadBalancerV2(id: UUID) = delete(id, classOf[LoadBalancerV2])

    override def getPoolV2(id: UUID): PoolV2 = get[PoolV2](id)

    override def getPoolsV2: util.List[PoolV2] = listAll(classOf[PoolV2])

    override def createPoolV2(pool: PoolV2): PoolV2 = create(pool)

    override def updatePoolV2(id: UUID, pool: PoolV2): PoolV2 = {
        pool.id = id
        update(pool)
    }

    override def deletePoolV2(id: UUID) = delete(id, classOf[PoolV2])

    override def getPoolMemberV2(id: UUID): PoolMemberV2 = get[PoolMemberV2](id)

    override def getPoolMembersV2: util.List[PoolMemberV2] = listAll(classOf[PoolMemberV2])

    override def createPoolMemberV2(member: PoolMemberV2): PoolMemberV2 = create(member)

    override def updatePoolMemberV2(id: UUID, member: PoolMemberV2): PoolMemberV2 = {
        member.id = id
        update(member)
    }

    override def deletePoolMemberV2(id: UUID) = delete(id, classOf[PoolMemberV2])

    override def getListenerV2(id: UUID): ListenerV2 = get[ListenerV2](id)

    override def getListenersV2: util.List[ListenerV2] = listAll(classOf[ListenerV2])

    override def createListenerV2(l: ListenerV2): ListenerV2 = create(l)

    override def updateListenerV2(id: UUID, l: ListenerV2): ListenerV2 = {
        l.id = id
        update(l)
    }

    override def deleteListenerV2(id: UUID) = delete(id, classOf[ListenerV2])

    override def getHealthMonitorV2(id: UUID): HealthMonitorV2 = get[HealthMonitorV2](id)

    override def getHealthMonitorsV2: util.List[HealthMonitorV2] = listAll(classOf[HealthMonitorV2])

    override def createHealthMonitorV2(healthMonitor: HealthMonitorV2): HealthMonitorV2 = create(healthMonitor)

    override def updateHealthMonitorV2(id: UUID, healthMonitor: HealthMonitorV2): HealthMonitorV2 = {
        healthMonitor.id = id
        update(healthMonitor)
    }

    override def deleteHealthMonitorV2(id: UUID) = delete(id, classOf[HealthMonitorV2])

    override def createSubnetBulk(subnets: util.List[Subnet])
    : util.List[Subnet] = bulkCreate(subnets)

    override def createNetworkBulk(networks: util.List[Network])
    : util.List[Network] = bulkCreate(networks)

    override def deleteNetwork(id: UUID): Unit = delete(id, classOf[Network])

    override def getNetwork(id: UUID): Network = get[Network](id)

    override def deletePort(id: UUID): Unit = delete(id, classOf[Port])

    override def deleteSubnet(id: UUID): Unit = delete(id, classOf[Subnet])

    override def getSubnet(id: UUID): Subnet = get[Subnet](id)

    override def getSubnets: util.List[Subnet] = listAll(classOf[Subnet])

    override def getNetworks: util.List[Network] = listAll(classOf[Network])

    override def updateSubnet(id: UUID, subnet: Subnet): Subnet = {
        subnet.id = id
        update(subnet)
    }

    override def getPort(id: UUID): Port = get[Port](id)

    override def createNetwork(dto: Network): Network = create(dto)

    override def createPort(dto: Port): Port = create(dto)

    override def getPorts: util.List[Port] = listAll(classOf[Port])

    override def updateNetwork(id: UUID, network: Network): Network = {
        network.id = id
        update(network)
    }

    override def createSubnet(dto: Subnet): Subnet = create(dto)

    override def createPortBulk(ports: util.List[Port]): util.List[Port] =
        bulkCreate(ports)

    override def createSecurityGroupRule(dto: SecurityGroupRule):
    SecurityGroupRule = {
        create(dto)
    }

    override def deleteSecurityGroup(id: UUID): Unit = {
        delete(id, classOf[SecurityGroup])
    }

    override def getSecurityGroupRule(id: UUID): SecurityGroupRule =
        get[SecurityGroupRule](id)

    override def updateSecurityGroup(id: UUID,
                                     sg: SecurityGroup): SecurityGroup = {
        sg.id = id
        update(sg)
    }

    override def createSecurityGroupBulk(sgs: util.List[SecurityGroup])
    : util.List[SecurityGroup] = bulkCreate(sgs)

    override def getSecurityGroups
    : util.List[SecurityGroup] = listAll(classOf[SecurityGroup])

    override def deleteSecurityGroupRule(id: UUID): Unit = {
        delete(id, classOf[SecurityGroupRule])
    }

    override def createSecurityGroup(dto: SecurityGroup): SecurityGroup =
        create(dto)

    override def getSecurityGroupRules
    : util.List[SecurityGroupRule] = listAll(classOf[SecurityGroupRule])

    override def getSecurityGroup(id: UUID): SecurityGroup =
        get[SecurityGroup](id)

    override def createSecurityGroupRuleBulk(rules: util
    .List[SecurityGroupRule]): util.List[SecurityGroupRule] = bulkCreate(rules)

    /* TODO: below are not in ZOOM and will require custom impl */
    override def createPoolHealthMonitor(id: UUID, phm: PoolHealthMonitor)
    : Unit = ???

    override def deletePoolHealthMonitor(poolId: UUID, hmId: UUID): Unit = ???

    override def createFirewall(dto: Firewall): Unit = create(dto)

    override def updateFirewall(dto: Firewall): Unit = update(dto)

    override def deleteFirewall(id: UUID): Unit = delete(id, classOf[Firewall])

    override def getVpnService(id: UUID): VpnService = get[VpnService](id)

    override def createVpnService(vpn: VpnService): Unit = create(vpn)

    override def updateVpnService(vpn: VpnService): Unit = update(vpn)

    override def deleteVpnService(id: UUID): Unit =
        delete(id, classOf[VpnService])

    override def getVpnServices: util.List[VpnService] =
        listAll(classOf[VpnService])

    override def getL2GatewayConnection(id: UUID): L2GatewayConnection =
        get[L2GatewayConnection](id)

    override def createL2GatewayConnection(l2GwConn: L2GatewayConnection)
    : Unit = create(l2GwConn)

    override def updateL2GatewayConnection(l2GwConn: L2GatewayConnection)
    : Unit = update(l2GwConn)

    override def deleteL2GatewayConnection(id: UUID): Unit =
        delete(id, classOf[L2GatewayConnection])

    override def getL2GatewayConnections: util.List[L2GatewayConnection] =
        listAll(classOf[L2GatewayConnection])

    override def getGatewayDevice(id: UUID): GatewayDevice =
        get[GatewayDevice](id)

    override def createGatewayDevice(gatewayDevice: GatewayDevice)
    : Unit = create(gatewayDevice)

    override def updateGatewayDevice(gatewayDevice: GatewayDevice)
    : Unit = update(gatewayDevice)

    override def deleteGatewayDevice(id: UUID): Unit =
        delete(id, classOf[GatewayDevice])

    override def getGatewayDevices: util.List[GatewayDevice] =
        listAll(classOf[GatewayDevice])

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    override def createRemoteMacEntry(entry: RemoteMacEntry): Unit =
        create(entry)

    override def deleteRemoteMacEntry(id: UUID): Unit =
        delete(id, classOf[RemoteMacEntry])

    @throws(classOf[NotFoundHttpException])
    override def getRemoteMacEntry(id: UUID): RemoteMacEntry =
        get[RemoteMacEntry](id)

    override def getRemoteMacEntries: util.List[RemoteMacEntry] =
        listAll(classOf[RemoteMacEntry])

    override def getIpSecSiteConnection(id: UUID): IPSecSiteConnection =
        get[IPSecSiteConnection](id)

    override def getIpSecSiteConnections: util.List[IPSecSiteConnection] =
        listAll(classOf[IPSecSiteConnection])

    override def createIpSecSiteConnection(cnxn: IPSecSiteConnection): Unit =
        create(cnxn)

    override def updateIpSecSiteConnection(cnxn: IPSecSiteConnection): Unit =
        update(cnxn)

    override def deleteIpSecSiteConnection(id: UUID): Unit =
        delete(id, classOf[IPSecSiteConnection])

    override def getBgpSpeaker(id: UUID): BgpSpeaker =
        get[BgpSpeaker](id)

    override def getBgpSpeakers: util.List[BgpSpeaker] =
        listAll(classOf[BgpSpeaker])

    override def createBgpSpeaker(bgpSpeaker: BgpSpeaker): Unit =
        create(bgpSpeaker)

    override def updateBgpSpeaker(bgpSpeaker: BgpSpeaker): Unit =
        update(bgpSpeaker)

    override def deleteBgpSpeaker(id: UUID): Unit =
        delete(id, classOf[BgpSpeaker])

    override def getBgpPeer(id: UUID): BgpPeer =
        get[BgpPeer](id)

    override def getBgpPeers: util.List[BgpPeer] =
        listAll(classOf[BgpPeer])

    override def createBgpPeer(bgpPeer: BgpPeer): Unit =
        create(bgpPeer)

    override def updateBgpPeer(bgpPeer: BgpPeer): Unit =
        update(bgpPeer)

    override def deleteBgpPeer(id: UUID): Unit =
        delete(id, classOf[BgpPeer])

    // TapFlow
    override def createTapFlow(flow: TapFlow): Unit = create(flow)
    override def updateTapFlow(flow: TapFlow): Unit = update(flow)
    override def deleteTapFlow(id: UUID): Unit = delete(id, classOf[TapFlow])
    override def getTapFlow(id: UUID): TapFlow = get[TapFlow](id)
    override def getTapFlows: util.List[TapFlow] = listAll(classOf[TapFlow])

    // TapService
    override def createTapService(service: TapService): Unit = create(service)
    override def updateTapService(service: TapService): Unit = update(service)
    override def deleteTapService(id: UUID): Unit =
        delete(id, classOf[TapService])
    override def getTapService(id: UUID): TapService = get[TapService](id)
    override def getTapServices: util.List[TapService] =
        listAll(classOf[TapService])

    // FirewallLog
    override def createFirewallLog(firewallLog: FirewallLog): Unit =
        create(firewallLog)
    override def updateFirewallLog(firewallLog: FirewallLog): Unit =
        update(firewallLog)
    override def deleteFirewallLog(id: UUID): Unit =
        delete(id, classOf[FirewallLog])

    // LoggingResource
    override def updateLoggingResource(loggingResource: LoggingResource)
    : Unit = update(loggingResource)
    override def deleteLoggingResource(id: UUID)
    : Unit = delete(id, classOf[LoggingResource])
}
