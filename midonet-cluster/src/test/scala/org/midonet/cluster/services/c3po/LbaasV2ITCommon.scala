/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.c3po

import java.util.UUID

import com.fasterxml.jackson.databind.JsonNode
import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{LbV2Pool => LbV2PoolType, LbV2PoolMember => LbV2PoolMemberType, LoadBalancerV2 => LoadBalancerV2Type, Port => PortType}
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerV2Pool.{LBV2SessionPersistenceType, LoadBalancerV2Protocol}
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.rest_api.neutron.models.PoolV2.LoadBalancerV2Algorithm
import org.midonet.packets.IPv4Addr

/**
  * Contains loadbalancer-related operations shared by multiple translators.
  */
trait LbaasV2ITCommon { this: C3POMinionTestBase =>

    protected def lbV2Json(id: UUID,
                           vipPortId: UUID,
                           vipAddress: String,
                           adminStateUp: Boolean = true): JsonNode = {
        val lb = nodeFactory.objectNode
        lb.put("id", id.toString)
        lb.put("admin_state_up", adminStateUp)
        lb.put("vip_port_id", vipPortId.toString)
        lb.put("vip_address", vipAddress)
        lb
    }

    protected def lbV2PoolJson(id: UUID = UUID.randomUUID(),
                               tenant_id: String = "tenant",
                               loadBalancerId: UUID,
                               name: Option[String] = None,
                               description: Option[String] = None,
                               adminStateUp: Boolean = true,
                               lbAlgorithm: LoadBalancerV2Algorithm = LoadBalancerV2Algorithm
                                   .ROUND_ROBIN,
                               members: Seq[UUID] = Seq(),
                               healthMonitorId: Option[UUID] = None,
                               listenerId: Option[UUID] = None,
                               listeners: Seq[UUID] = Seq(),
                               protocol: LoadBalancerV2Protocol = LoadBalancerV2Protocol
                                   .TCP,
                               sessionPersistence: Option[JsonNode] = None)
    : JsonNode = {
        val p = nodeFactory.objectNode
        p.put("id", id.toString)
        p.put("tenant_id", tenant_id)
        p.put("name", name.getOrElse(s"lbv2pool-$id"))
        p.put("description", description.getOrElse(s"LBV2 Pool with id $id"))
        p.put("admin_state_up", adminStateUp)
        p.put("lb_algorithm", lbAlgorithm.toString)
        val membersArr = p.putArray("members")
        for (m <- members) {
            membersArr.add(m.toString)
        }
        healthMonitorId.foreach(id => p.put("health_monitor_id", id.toString))
        listenerId.foreach(id => p.put("listener_id", id.toString))
        val listenersArr = p.putArray("listeners")
        for (l <- listeners) {
            listenersArr.add(l.toString)
        }
        val loadBalancersArr = p.putArray("loadbalancers")
        loadBalancersArr.add(loadBalancerId.toString)
        p.put("protocol", protocol.toString)
        sessionPersistence.foreach(n => p.set("session_persistence", n))
        p
    }

    protected def lbv2PoolMemberJson(id: UUID = UUID.randomUUID(),
                                     tenantId: String = "tenant",
                                     address: String = IPv4Addr.random.toString,
                                     adminStateUp: Boolean = true,
                                     protocolPort: Int = 10000,
                                     weight: Int = 1,
                                     subnetId: Option[UUID] = None,
                                     name: Option[String] = None,
                                     poolId: Option[UUID] = None): JsonNode = {
        val pm = nodeFactory.objectNode
        pm.put("id", id.toString)
        pm.put("tenant_id", tenantId)
        pm.put("address", address)
        pm.put("admin_state_up", adminStateUp)
        pm.put("protocol_port", protocolPort)
        pm.put("weight", weight)
        subnetId.foreach(id => pm.put("subnet_id", id.toString))
        pm.put("name", name.getOrElse(s"lbv2poolmember-$id"))
        poolId.foreach(id => pm.put("pool_id", id.toString))
        pm
    }

    protected def lbV2SessionPersistenceJson(
            typ: LBV2SessionPersistenceType = LBV2SessionPersistenceType.SOURCE_IP,
            cookieName: Option[String] = None): JsonNode = {
        val sp = nodeFactory.objectNode
        sp.put("type", typ.toString)
        cookieName.foreach(sp.put("cookie_name", _))
        sp
    }

    protected def lbv2ListenerJson(id: UUID = UUID.randomUUID(),
                                   loadBalancerId: UUID,
                                   defaultPoolId: Option[UUID] = None,
                                   tenantId: String = "tenant",
                                   adminStateUp: Boolean = true,
                                   protocolPort: Int = 10000): JsonNode = {
        val pm = nodeFactory.objectNode
        pm.put("id", id.toString)
        val loadBalancersArr = pm.putArray("loadbalancers")
        loadBalancersArr.add(loadBalancerId.toString)
        defaultPoolId.foreach(id => pm.put("default_pool_id", id.toString))
        pm.put("tenant_id", tenantId)
        pm.put("admin_state_up", adminStateUp)
        pm.put("protocol_port", protocolPort)
        pm
    }

    protected def createVipV2Port(taskId: Int, networkId: UUID, subnetId: UUID,
                                  ipAddr: String,
                                  portId: UUID = UUID.randomUUID()): UUID = {
        val json = portJson(portId, networkId,
                            deviceOwner = DeviceOwner.LOADBALANCERV2,
                            fixedIps = List(IPAlloc(ipAddr, subnetId)))
        insertCreateTask(taskId, PortType, json, portId)
        portId
    }

    protected def createVipV2PortAndNetwork(taskId: Int,
                                            ipAddr: String = "10.0.1.4",
                                            netAddr: String = "10.0.1.0/24",
                                            portId: UUID = UUID.randomUUID()):
    (UUID, UUID, UUID) = {
        val vipNetworkId = createTenantNetwork(taskId)
        val vipSubnetId = createSubnet(taskId + 1, vipNetworkId, netAddr)
        val json = portJson(portId, vipNetworkId,
                            deviceOwner = DeviceOwner.LOADBALANCERV2,
                            fixedIps = List(IPAlloc(ipAddr, vipSubnetId)))
        (createVipV2Port(taskId + 2, vipNetworkId, vipSubnetId, ipAddr, portId),
            vipNetworkId,
            vipSubnetId)
    }

    protected def createLbV2(taskId: Int, vipPortId: UUID, vipAddress: String,
                             id: UUID = UUID.randomUUID(),
                             adminStateUp: Boolean = true): UUID = {
        val json = lbV2Json(id, vipPortId, vipAddress, adminStateUp)
        insertCreateTask(taskId, LoadBalancerV2Type, json, id)
        id
    }

    protected def createLbV2Pool(taskId: Int, loadBalancerId: UUID,
                                 id: UUID = UUID.randomUUID(),
                                 listenerId: Option[UUID] = None,
                                 adminStateUp: Boolean = true,
                                 healthMonitorId: Option[UUID] = None,
                                 sessionPersistenceType: Option[LBV2SessionPersistenceType] = None)
    : UUID = {
        val sessionPersistenceJson = sessionPersistenceType.map(
            typ => lbV2SessionPersistenceJson(typ))
        val json = lbV2PoolJson(id = id, adminStateUp = adminStateUp,
                                healthMonitorId = healthMonitorId,
                                listenerId = listenerId,
                                loadBalancerId = loadBalancerId,
                                sessionPersistence = sessionPersistenceJson)
        insertCreateTask(taskId, LbV2PoolType, json, id)
        id
    }

    protected def createLbV2PoolMember(taskId: Int, poolId: UUID,
                                       subnetId: UUID,
                                       id: UUID = UUID.randomUUID(),
                                       adminStateUp: Boolean = true,
                                       address: String = IPv4Addr.random
                                           .toString,
                                       protocolPort: Int = 10000,
                                       weight: Int = 1): UUID = {
        val json = lbv2PoolMemberJson(id = id, poolId = poolId,
                                      subnetId = subnetId,
                                      adminStateUp = adminStateUp,
                                      weight = weight, address = address,
                                      protocolPort = protocolPort)
        insertCreateTask(taskId, LbV2PoolMemberType, json, id)
        id
    }
}
