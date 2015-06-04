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

package org.midonet.cluster.neutron_rest_api

import java.util
import java.util.UUID

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.fromExecutor
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.reflect.ClassTag

import com.google.common.util.concurrent.MoreExecutors
import com.google.inject.Inject
import com.google.protobuf.Message

import org.midonet.cluster.data.ZoomConvert.{fromProto, toProto}
import org.midonet.cluster.data.neutron._
import org.midonet.cluster.data.neutron.loadbalancer._
import org.midonet.cluster.data.storage.PersistenceOp
import org.midonet.cluster.data.{ZoomOneOf, ZoomClass, ZoomObject}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.c3po.{C3POStorageManager, neutron}
import org.midonet.cluster.util.UUIDUtil

class NeutronZoomPlugin @Inject()(c3po: C3POStorageManager,
                                  backend: MidonetBackend) extends L3Api
                with LoadBalancerApi with NetworkApi with SecurityGroupApi {

    private val timeout = 10.seconds
    private implicit val ec = fromExecutor(MoreExecutors.sameThreadExecutor())

    def create[T >: Null <: ZoomObject](dto: T)(implicit ct: ClassTag[T]): T = {
        val protoClass = protoClassOf(dto)
        val neutronOp = neutron.Create(toProto(dto, protoClass))
        val persistenceOps = c3po.toPersistenceOps(neutronOp)
        val id = idOf(neutronOp.model)
        backend.store.multi(persistenceOps)
        get[T](id)
    }

    def bulkCreate[T >: Null <: ZoomObject](dtos: util.List[T])
                                   (implicit ct: ClassTag[T]): util.List[T] = {
        if (dtos.isEmpty) {
            return List.empty[T]
        }

        val protoClass = dtos.head.getClass
                                  .getAnnotation(classOf[ZoomClass]).clazz()
        val creates = dtos.map { d => neutron.Create(toProto(d, protoClass)) }
        val ops = creates.foldLeft(List.empty[PersistenceOp]) { (acc, cr) =>
            acc ++ c3po.toPersistenceOps(cr)
        }

        backend.store.multi(ops)
        list[T](creates.map(c => idOf(c.model)))
    }

    def get[T >: Null <: ZoomObject](id: UUID)(implicit ct: ClassTag[T]): T = {
        val dtoClass = ct.runtimeClass.asInstanceOf[Class[T]]
        val proto = Await.result(
            backend.store.get(protoClassOf(dtoClass), id), timeout)
        fromProto(proto, dtoClass)
    }

    def list[T >: Null <: ZoomObject](ids: util.List[UUID])
                                    (implicit ct: ClassTag[T]): util.List[T] = {
        val dtoClass = ct.runtimeClass.asInstanceOf[Class[T]]
        val protoClass = protoClassOf(dtoClass)
        val results = Future.sequence (
            ids map { id =>
                backend.store.get(protoClass, id).map(fromProto(_, dtoClass))
            }
        )
        Await.result(results, timeout).toList
    }

    def listAll[T >: Null <: ZoomObject](dtoClass: Class[T]): util.List[T] = {
        val res = Await.result (
            backend.store.getAll(protoClassOf(dtoClass)), timeout
        ) map (
            fromProto(_, dtoClass.asInstanceOf[Class[T]])
        )
        res.toList
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
        dtoClass.getAnnotation(classOf[ZoomClass]).clazz()
    }

    override def createRouter(dto: Router): Router = create(dto)

    override def addRouterInterface(routerId: UUID,
                                    routerInterface: RouterInterface): RouterInterface = ???

    override def removeRouterInterface(routerId: UUID,
                                       routerInterface: RouterInterface): RouterInterface = ???

    override def updateFloatingIp(id: UUID,
                                  floatingIp: FloatingIp): FloatingIp = ???

    override def getFloatingIp(id: UUID): FloatingIp = get[FloatingIp](id)

    override def getRouters: util.List[Router] = listAll(classOf[Router])

    override def createFloatingIp(dto: FloatingIp): FloatingIp = create(dto)

    override def deleteFloatingIp(id: UUID): Unit = ???

    override def deleteRouter(id: UUID): Unit = ???

    override def updateRouter(id: UUID, router: Router): Router = ???

    override def getRouter(id: UUID): Router = get[Router](id)

    override def getFloatingIps
    : util.List[FloatingIp] = listAll(classOf[FloatingIp])

    override def getMembers: util.List[Member] = listAll(classOf[Member])

    override def deletePool(id: UUID): Unit = ???

    override def getHealthMonitors
    : util.List[HealthMonitor] = listAll(classOf[HealthMonitor])

    override def createVip(dto: VIP): Unit = create[VIP](dto)

    override def getMember(id: UUID): Member = get[Member](id)

    override def getPool(id: UUID): Pool = get[Pool](id)

    override def deleteMember(id: UUID): Unit = ???

    override def updateVip(id: UUID, vip: VIP): Unit = ???

    override def updateMember(id: UUID, member: Member): Unit = ???

    override def createPool(dto: Pool): Unit = create[Pool](dto)

    override def getVip(id: UUID): VIP = get[VIP](id)

    override def createPoolHealthMonitor(id: UUID,
                                         poolHealthMonitor: PoolHealthMonitor): Unit = ???

    override def createMember(dto: Member): Unit = create[Member](dto)

    override def deleteVip(id: UUID): Unit = ???

    override def updateHealthMonitor(id: UUID,
                                     healthMonitor: HealthMonitor): Unit = ???

    override def deleteHealthMonitor(id: UUID): Unit = ???

    override def updatePool(id: UUID, pool: Pool): Unit = ???

    override def getVips: util.List[VIP] = listAll(classOf[VIP])

    override def getPools: util.List[Pool] = listAll(classOf[Pool])

    override def createHealthMonitor(dto: HealthMonitor): Unit =
        create[HealthMonitor](dto)

    override def getHealthMonitor(id: UUID): HealthMonitor = ???

    override def deletePoolHealthMonitor(poolId: UUID, hmId: UUID): Unit = ???

    override def updatePort(id: UUID, port: Port): Port = ???

    override def createSubnetBulk(subnets: util.List[Subnet])
    : util.List[Subnet] = bulkCreate(subnets)

    override def createNetworkBulk(networks: util.List[Network])
    : util.List[Network] = bulkCreate(networks)

    override def deleteNetwork(id: UUID): Unit = ???

    override def getNetwork(id: UUID): Network = get[Network](id)

    override def deletePort(id: UUID): Unit = ???

    override def deleteSubnet(id: UUID): Unit = ???

    override def getSubnet(id: UUID): Subnet = get[Subnet](id)

    override def getSubnets: util.List[Subnet] = listAll(classOf[Subnet])

    override def getNetworks: util.List[Network] = listAll(classOf[Network])

    override def updateSubnet(id: UUID, subnet: Subnet): Subnet = ???

    override def getPort(id: UUID): Port = get[Port](id)

    override def createNetwork(dto: Network): Network = create(dto)

    override def createPort(dto: Port): Port = create(dto)

    override def getPorts: util.List[Port] = listAll(classOf[Port])

    override def updateNetwork(id: UUID, network: Network): Network = ???

    override def createSubnet(dto: Subnet): Subnet = create(dto)

    override def createPortBulk(ports: util.List[Port]): util.List[Port] =
        bulkCreate(ports)

    override def createSecurityGroupRule(dto: SecurityGroupRule):
    SecurityGroupRule = ???

    override def deleteSecurityGroup(id: UUID): Unit = ???

    override def getSecurityGroupRule(id: UUID): SecurityGroupRule = get(id)

    override def updateSecurityGroup(id: UUID,
                                     sg: SecurityGroup): SecurityGroup = ???

    override def createSecurityGroupBulk(sgs: util.List[SecurityGroup])
    : util.List[SecurityGroup] = bulkCreate(sgs)

    override def getSecurityGroups
    : util.List[SecurityGroup] = listAll(classOf[SecurityGroup])

    override def deleteSecurityGroupRule(id: UUID): Unit = ???

    override def createSecurityGroup(dto: SecurityGroup): SecurityGroup =
        create(dto)

    override def getSecurityGroupRules
    : util.List[SecurityGroupRule] = listAll(classOf[SecurityGroupRule])

    override def getSecurityGroup(id: UUID): SecurityGroup = get(id)

    override def createSecurityGroupRuleBulk(rules: util
    .List[SecurityGroupRule]): util.List[SecurityGroupRule] = bulkCreate(rules)
}
