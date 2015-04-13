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

package org.midonet.brain.services.rest_api

import java.lang.annotation.Annotation
import java.lang.reflect.Field
import java.util.UUID

import scala.collection.mutable

import com.google.protobuf.GeneratedMessage.Builder
import com.google.protobuf.MessageOrBuilder

import org.midonet.brain.services.rest_api.annotation._
import org.midonet.brain.services.rest_api.models._
import org.midonet.cluster.data.{ZoomConvert, ZoomField, ZoomClass}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.collection.Bimap

/** All the MediaTypes offered by MidoNet, plus some utility lookups into maps
  * between domains. */
object MidonetMediaTypes {

    import ResourceUris._

    type DtoClass = Class[_ >: Null <: UriResource]
    type DtoPath = Seq[String]
    private type ZoomBuilder = Builder[_ <: Builder[_ <: AnyRef]]

    private final val BuilderMethod = "newBuilder"
    private final val IdField = "id"

    final val dtos = Set[DtoClass](
        classOf[Bridge],
        classOf[BridgePort],
        classOf[Chain],
        classOf[DhcpHost],
        classOf[DhcpSubnet],
        classOf[Host],
        classOf[Interface],
        classOf[Port],
        classOf[Router],
        classOf[Rule],
        classOf[TunnelZone],
        classOf[TunnelZoneHost]
    )

    /**
     * An attribute containing the following information about a specific DTO
     * resource:
     * - the DTO resource path, containing all parent resources
     * - the DTO class
     * - the [[Resource]] annotation
     * - the ZOOM Protocol Buffers class corresponding to the DTO class
     * - the attribute for the identifier field, if it exists
     * - the attribute for the parent identifier field, if it exists
     * - the attribute for the ownership field, if it exists
     * - a mapping for all fields that correspond to a sub-resource of this
     * resource.
     */
    case class DtoAttribute(path: DtoPath, clazz: DtoClass, resource: Resource,
                            zoom: ZoomClass, id: FieldAttribute,
                            parent: FieldAttribute, ownership: FieldAttribute,
                            subresources: Map[String, FieldAttribute]) {
        /** Sets the identifier for the given DTO. */
        def setId(dto: UriResource, dtoId: String): Unit = {
            if (id eq null) return
            if (id.field.getType == classOf[UUID])
                parent.field.set(dto, UUID.fromString(dtoId))
            else if (parent.field.getType == classOf[String])
                parent.field.set(dto, dtoId)
        }

        def getProtoId(dtoId: String): String = {
            if (id eq null) return dtoId
            if (id.zoom eq null) return dtoId
            if (id.field.getType == classOf[UUID]) return dtoId
            val converter = id.zoom.converter().newInstance()
                .asInstanceOf[ZoomConvert.Converter[Any, Commons.UUID]]
            converter.toProto(dtoId, classOf[String]).asJava.toString
        }

        /** Generates a new identifier for the given DTO, if the DTO does not
          * have one.*/
        def generateId(dto: UriResource): Unit = {
            if (id eq null) return
            if (id.field.get(dto) == null) {
                val uuid = UUID.randomUUID
                if (id.field.getType == classOf[UUID])
                    id.field.set(dto, uuid)
                else if (id.field.getType == classOf[String])
                    id.field.set(dto, uuid.toString)
            }
        }

        /** Sets the parent identifier for the given DTO. */
        def setParentId(dto: UriResource, parentId: String): Unit = {
            if (parent eq null) return
            if (parent.field.getType == classOf[UUID])
                parent.field.set(dto, UUID.fromString(parentId))
            else if (parent.field.getType == classOf[String])
                parent.field.set(dto, parentId)
        }

        /** Sets the ownership for the given DTO. */
        def setOwners(dto: UriResource, owners: Set[String]): Unit = {
            if (ownership eq null) return
            if (ownership.field.getType == classOf[Boolean])
                ownership.field.set(dto, owners.nonEmpty)
        }

        /** Gets the Protocol Buffers descriptor corresponding to this DTO
          * attribute. */
        def getMessageDescriptor = {
            zoom.clazz
                .getMethod(BuilderMethod).invoke(null).asInstanceOf[ZoomBuilder]
                .getDescriptorForType
        }

        /** Gets the Protocol Buffers descriptor corresponding to this DTO
          * attribute for the given field name. */
        def getFieldDescriptor(name: String) = {
            getMessageDescriptor.findFieldByName(name)
        }

        /** Gets the Protocol Buffers descriptor corresponding to this DTO
          * attribute for the given subresource. */
        def getSubresourceDescriptor(subresource: String) = {
            getMessageDescriptor.findFieldByName(subresources(subresource)
                                                     .zoom.name)
        }

        /** Gets the value of the field annotated with a [[ResourceId]]
          * annotation for the specified Protocol Buffers message. */
        def getId(message: MessageOrBuilder): String = {
            val idDescriptor = getFieldDescriptor(id.zoom.name)
            message.getField(idDescriptor) match {
                case id: Commons.UUID => id.asJava.toString
                case id: String => id
                case any => any.toString
            }
        }

        def hasProtoId(message: MessageOrBuilder): Boolean = {
            getFieldDescriptor(IdField) ne null
        }

        /** Gets the value of the `id` field for the specified Protocol Buffers
          * message. */
        def getProtoId(message: MessageOrBuilder): String = {
            message.getField(getFieldDescriptor(IdField))
                   .asInstanceOf[Commons.UUID].asJava.toString
        }

        /** Indicates whether the underlying message for this resource can be
          * saved in storage. This is true when this is either a root resource,
          * or when is referenced by its parent via an UUID list. */
        def isStorable: Boolean = {
            if (path.length == 1) return true
            val parentAttr = ResourceAttributes(path.dropRight(1))
            val parentDesc = parentAttr.getSubresourceDescriptor(resource.name)
            parentDesc.isRepeated &&
            parentDesc.getMessageType == Commons.UUID.getDescriptor
        }

        /** Returns a new Protocol Buffers builder for the current resouurce,
          * initialized from the specified prototype. */
        def newBuilder(prototype: MessageOrBuilder): ZoomBuilder = {
            zoom.clazz.getMethod(BuilderMethod, zoom.clazz)
                .invoke(null, prototype)
                .asInstanceOf[ZoomBuilder]
        }
    }

    /**
     * An attribute for a field of a DTO resource, which includes the following
     * information:
     * - the field [[Field]] reflection instance
     * - the [[Subresource]] annotation if this field corresponds to a sub-
     * resource
     * - the [[ZoomField]] annotation if this field corresponds to a Protocol
     * Buffers message field
     */
    case class FieldAttribute(field: Field, subsresource: Subresource,
                              zoom: ZoomField)

    final val ResourceAttributes = getResourceAttributes

    final val APPLICATION_JSON_V4 = "application/vnd.org.midonet.Application-v4+json"
    final val APPLICATION_JSON_V5 = "application/vnd.org.midonet.Application-v5+json"

    final val APPLICATION_ERROR_JSON = "application/vnd.org.midonet.Error-v1+json"
    final val APPLICATION_TENANT_JSON = "application/vnd.org.midonet.Tenant-v1+json"
    final val APPLICATION_TENANT_COLLECTION_JSON = "application/vnd.org.midonet.collection.Tenant-v1+json"
    final val APPLICATION_ROUTER_JSON_V2 = "application/vnd.org.midonet.Router-v2+json"
    final val APPLICATION_ROUTER_JSON = "application/vnd.org.midonet.Router-v1+json"
    final val APPLICATION_ROUTER_COLLECTION_JSON = "application/vnd.org.midonet.collection.Router-v1+json"
    final val APPLICATION_ROUTER_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.Router-v2+json"
    final val APPLICATION_BRIDGE_JSON = "application/vnd.org.midonet.Bridge-v1+json"
    final val APPLICATION_BRIDGE_JSON_V2 = "application/vnd.org.midonet.Bridge-v2+json"
    final val APPLICATION_BRIDGE_JSON_V3 = "application/vnd.org.midonet.Bridge-v3+json"
    final val APPLICATION_BRIDGE_COLLECTION_JSON = "application/vnd.org.midonet.collection.Bridge-v1+json"
    final val APPLICATION_BRIDGE_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.Bridge-v2+json"
    final val APPLICATION_BRIDGE_COLLECTION_JSON_V3 = "application/vnd.org.midonet.collection.Bridge-v3+json"
    final val APPLICATION_MAC_PORT_JSON = "application/vnd.org.midonet.MacPort-v1+json"
    final val APPLICATION_MAC_PORT_JSON_V2 = "application/vnd.org.midonet.MacPort-v2+json"
    final val APPLICATION_MAC_PORT_COLLECTION_JSON = "application/vnd.org.midonet.collection.MacPort-v1+json"
    final val APPLICATION_MAC_PORT_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.MacPort-v2+json"
    final val APPLICATION_IP4_MAC_JSON = "application/vnd.org.midonet.IP4Mac-v1+json"
    final val APPLICATION_IP4_MAC_COLLECTION_JSON = "application/vnd.org.midonet.collection.IP4Mac-v1+json"
    final val APPLICATION_HOST_JSON_V2 = "application/vnd.org.midonet.Host-v2+json"
    final val APPLICATION_HOST_JSON_V3 = "application/vnd.org.midonet.Host-v3+json"
    final val APPLICATION_HOST_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.Host-v2+json"
    final val APPLICATION_HOST_COLLECTION_JSON_V3 = "application/vnd.org.midonet.collection.Host-v3+json"
    final val APPLICATION_INTERFACE_JSON = "application/vnd.org.midonet.Interface-v1+json"
    final val APPLICATION_INTERFACE_COLLECTION_JSON = "application/vnd.org.midonet.collection.Interface-v1+json"
    final val APPLICATION_PORT_JSON = "application/vnd.org.midonet.Port-v1+json"
    final val APPLICATION_PORT_COLLECTION_JSON = "application/vnd.org.midonet.collection.Port-v1+json"
    final val APPLICATION_PORT_V2_JSON = "application/vnd.org.midonet.Port-v2+json"
    final val APPLICATION_PORT_V2_COLLECTION_JSON = "application/vnd.org.midonet.collection.Port-v2+json"
    final val APPLICATION_PORT_LINK_JSON = "application/vnd.org.midonet.PortLink-v1+json"
    final val APPLICATION_ROUTE_JSON = "application/vnd.org.midonet.Route-v1+json"
    final val APPLICATION_ROUTE_COLLECTION_JSON = "application/vnd.org.midonet.collection.Route-v1+json"
    final val APPLICATION_PORTGROUP_JSON = "application/vnd.org.midonet.PortGroup-v1+json"
    final val APPLICATION_PORTGROUP_COLLECTION_JSON = "application/vnd.org.midonet.collection.PortGroup-v1+json"
    final val APPLICATION_PORTGROUP_PORT_JSON = "application/vnd.org.midonet.PortGroupPort-v1+json"
    final val APPLICATION_PORTGROUP_PORT_COLLECTION_JSON = "application/vnd.org.midonet.collection.PortGroupPort-v1+json"
    final val APPLICATION_CHAIN_JSON = "application/vnd.org.midonet.Chain-v1+json"
    final val APPLICATION_CHAIN_COLLECTION_JSON = "application/vnd.org.midonet.collection.Chain-v1+json"
    final val APPLICATION_RULE_JSON = "application/vnd.org.midonet.Rule-v1+json"
    final val APPLICATION_RULE_COLLECTION_JSON = "application/vnd.org.midonet.collection.Rule-v1+json"
    final val APPLICATION_RULE_JSON_V2 = "application/vnd.org.midonet.Rule-v2+json"
    final val APPLICATION_RULE_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.Rule-v2+json"
    final val APPLICATION_BGP_JSON = "application/vnd.org.midonet.Bgp-v1+json"
    final val APPLICATION_BGP_COLLECTION_JSON = "application/vnd.org.midonet.collection.Bgp-v1+json"
    final val APPLICATION_AD_ROUTE_JSON = "application/vnd.org.midonet.AdRoute-v1+json"
    final val APPLICATION_AD_ROUTE_COLLECTION_JSON = "application/vnd.org.midonet.collection.AdRoute-v1+json"

    /* DHCP configuration types. */
    final val APPLICATION_DHCP_SUBNET_JSON = "application/vnd.org.midonet.DhcpSubnet-v1+json"
    final val APPLICATION_DHCP_SUBNET_JSON_V2 = "application/vnd.org.midonet.DhcpSubnet-v2+json"
    final val APPLICATION_DHCP_SUBNET_COLLECTION_JSON = "application/vnd.org.midonet.collection.DhcpSubnet-v1+json"
    final val APPLICATION_DHCP_SUBNET_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.DhcpSubnet-v2+json"
    final val APPLICATION_DHCP_HOST_JSON = "application/vnd.org.midonet.DhcpHost-v1+json"
    final val APPLICATION_DHCP_HOST_COLLECTION_JSON = "application/vnd.org.midonet.collection.DhcpHost-v1+json"
    final val APPLICATION_DHCP_HOST_JSON_V2 = "application/vnd.org.midonet.DhcpHost-v2+json"
    final val APPLICATION_DHCP_HOST_COLLECTION_JSON_V2 = "application/vnd.org.midonet.collection.DhcpHost-v2+json"
    final val APPLICATION_DHCPV6_SUBNET_JSON = "application/vnd.org.midonet.DhcpV6Subnet-v1+json"
    final val APPLICATION_DHCPV6_SUBNET_COLLECTION_JSON = "application/vnd.org.midonet.collection.DhcpV6Subnet-v1+json"
    final val APPLICATION_DHCPV6_HOST_JSON = "application/vnd.org.midonet.DhcpV6Host-v1+json"
    final val APPLICATION_DHCPV6_HOST_COLLECTION_JSON = "application/vnd.org.midonet.collection.DhcpV6Host-v1+json"

    // Tunnel Zones
    final val APPLICATION_TUNNEL_ZONE_JSON = "application/vnd.org.midonet.TunnelZone-v1+json"
    final val APPLICATION_TUNNEL_ZONE_COLLECTION_JSON = "application/vnd.org.midonet.collection.TunnelZone-v1+json"

    final val APPLICATION_TUNNEL_ZONE_HOST_JSON = "application/vnd.org.midonet.TunnelZoneHost-v1+json"
    final val APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON = "application/vnd.org.midonet.collection.TunnelZoneHost-v1+json"
    final val APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON = "application/vnd.org.midonet.GreTunnelZoneHost-v1+json"
    final val APPLICATION_GRE_TUNNEL_ZONE_HOST_COLLECTION_JSON = "application/vnd.org.midonet.collection.GreTunnelZoneHost-v1+json"

    // Host interface - port mapping
    final val APPLICATION_HOST_INTERFACE_PORT_JSON = "application/vnd.org.midonet.HostInterfacePort-v1+json"
    final val APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON = "application/vnd.org.midonet.collection.HostInterfacePort-v1+json"

    // Upgrade Control
    final val APPLICATION_WRITE_VERSION_JSON = "application/vnd.org.midonet.WriteVersion-v1+json"
    final val APPLICATION_SYSTEM_STATE_JSON = "application/vnd.org.midonet.SystemState-v1+json"
    final val APPLICATION_SYSTEM_STATE_JSON_V2 = "application/vnd.org.midonet.SystemState-v2+json"
    final val APPLICATION_HOST_VERSION_JSON = "application/vnd.org.midonet.HostVersion-v1+json"

    // L4LB
    final val APPLICATION_HEALTH_MONITOR_JSON = "application/vnd.org.midonet.HealthMonitor-v1+json"
    final val APPLICATION_HEALTH_MONITOR_COLLECTION_JSON = "application/vnd.org.midonet.collection.HealthMonitor-v1+json"
    final val APPLICATION_LOAD_BALANCER_JSON = "application/vnd.org.midonet.LoadBalancer-v1+json"
    final val APPLICATION_LOAD_BALANCER_COLLECTION_JSON = "application/vnd.org.midonet.collection.LoadBalancer-v1+json"
    final val APPLICATION_POOL_MEMBER_JSON = "application/vnd.org.midonet.PoolMember-v1+json"
    final val APPLICATION_POOL_MEMBER_COLLECTION_JSON = "application/vnd.org.midonet.collection.PoolMember-v1+json"
    final val APPLICATION_POOL_JSON = "application/vnd.org.midonet.Pool-v1+json"
    final val APPLICATION_POOL_COLLECTION_JSON = "application/vnd.org.midonet.collection.Pool-v1+json"
    final val APPLICATION_VIP_JSON = "application/vnd.org.midonet.VIP-v1+json"
    final val APPLICATION_VIP_COLLECTION_JSON = "application/vnd.org.midonet.collection.VIP-v1+json"

    // VXGW
    final val APPLICATION_VTEP_JSON = "application/vnd.org.midonet.VTEP-v1+json"
    final val APPLICATION_VTEP_COLLECTION_JSON = "application/vnd.org.midonet.collection.VTEP-v1+json"
    final val APPLICATION_VTEP_BINDING_JSON = "application/vnd.org.midonet.VTEPBinding-v1+json"
    final val APPLICATION_VTEP_BINDING_COLLECTION_JSON = "application/vnd.org.midonet.collection.VTEPBinding-v1+json"
    final val APPLICATION_VTEP_PORT_COLLECTION_JSON = "application/vnd.org.midonet.collection.VTEPPort-v1+json"

    // Token Information
    final val APPLICATION_TOKEN_JSON = "application/vnd.org.midonet.Token-v1+json"

    final val APPLICATION_IP_ADDR_GROUP_JSON = "application/vnd.org.midonet.IpAddrGroup-v1+json"
    final val APPLICATION_IP_ADDR_GROUP_COLLECTION_JSON = "application/vnd.org.midonet.collection.IpAddrGroup-v1+json"

    final val APPLICATION_IP_ADDR_GROUP_ADDR_JSON = "application/vnd.org.midonet.IpAddrGroupAddr-v1+json"
    final val APPLICATION_IP_ADDR_GROUP_ADDR_COLLECTION_JSON = "application/vnd.org.midonet.collection.IpAddrGroupAddr-v1+json"

    final val APPLICATION_TRACE_REQUEST_JSON = "application/vnd.org.midonet.TraceRequest-v1+json"
    final val APPLICATION_TRACE_REQUEST_COLLECTION_JSON = "application/vnd.org.midonet.collection.TraceRequest-v1+json"

    val resourceNames = Bimap[String, String](Map(
        APPLICATION_BRIDGE_JSON -> BRIDGES,
        APPLICATION_BRIDGE_JSON_V2 -> BRIDGES,
        APPLICATION_BRIDGE_JSON_V3 -> BRIDGES,
        APPLICATION_HOST_JSON_V2 -> HOSTS,
        APPLICATION_HOST_JSON_V3 -> HOSTS,
        APPLICATION_PORT_JSON -> PORTS,
        APPLICATION_PORT_V2_JSON-> PORTS,
        APPLICATION_ROUTER_JSON -> ROUTERS,
        APPLICATION_ROUTER_JSON_V2 -> ROUTERS,
        APPLICATION_TUNNEL_ZONE_JSON -> TUNNEL_ZONES,
        // APPLICATION_ERROR_JSON -> ERRORS,
        APPLICATION_TENANT_JSON -> TENANTS,
        // APPLICATION_MAC_PORT_JSON -> MAC_PORTS,
        // APPLICATION_MAC_PORT_JSON_V2 -> MAC_PORTS,
        // APPLICATION_IP4_MAC_JSON ->
        // APPLICATION_INTERFACE_JSON ->
        APPLICATION_PORT_JSON -> PORTS,
        APPLICATION_PORT_V2_JSON -> PORTS,
        // APPLICATION_PORT_LINK_JSON ->
        APPLICATION_ROUTE_JSON -> ROUTES,
        APPLICATION_PORTGROUP_JSON -> PORT_GROUPS,
        APPLICATION_PORTGROUP_PORT_JSON -> PORT_GROUPS,
        APPLICATION_CHAIN_JSON -> CHAINS,
        APPLICATION_RULE_JSON -> RULES,
        APPLICATION_RULE_JSON_V2 -> RULES,
        APPLICATION_BGP_JSON -> BGP,
        APPLICATION_AD_ROUTE_JSON -> AD_ROUTES,
        APPLICATION_DHCP_SUBNET_JSON -> DHCP,
        APPLICATION_DHCP_SUBNET_JSON_V2 -> DHCP,
        APPLICATION_DHCP_HOST_JSON -> DHCP_HOSTS,
        APPLICATION_DHCP_HOST_JSON_V2 -> DHCP_HOSTS,
        APPLICATION_DHCPV6_SUBNET_JSON -> DHCPV6,
        APPLICATION_DHCPV6_HOST_JSON -> DHCPV6_HOSTS,
        APPLICATION_TUNNEL_ZONE_JSON -> TUNNEL_ZONES,
        APPLICATION_TUNNEL_ZONE_HOST_JSON -> TUNNEL_ZONES,
        // APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON ->
        // APPLICATION_HOST_INTERFACE_PORT_JSON ->
        APPLICATION_WRITE_VERSION_JSON -> WRITE_VERSION,
        APPLICATION_SYSTEM_STATE_JSON -> SYSTEM_STATE,
        APPLICATION_SYSTEM_STATE_JSON_V2 -> SYSTEM_STATE,
        APPLICATION_HOST_VERSION_JSON -> HOSTS,
        APPLICATION_HEALTH_MONITOR_JSON -> HEALTH_MONITORS,
        APPLICATION_LOAD_BALANCER_JSON -> LOAD_BALANCERS,
        APPLICATION_POOL_MEMBER_JSON -> POOL_MEMBERS,
        APPLICATION_POOL_JSON -> POOLS,
        APPLICATION_VIP_JSON -> VIPS,
        APPLICATION_VTEP_JSON -> VTEPS,
        APPLICATION_VTEP_BINDING_JSON -> VTEP_BINDINGS,
        APPLICATION_IP_ADDR_GROUP_JSON -> IP_ADDR_GROUPS,
        APPLICATION_IP_ADDR_GROUP_ADDR_JSON -> IP_ADDR_GROUPS,
        APPLICATION_TRACE_REQUEST_JSON -> TRACE_REQUESTS
    ))

    private def getResourceAttributes: Map[DtoPath, DtoAttribute] = {
        val map = new mutable.HashMap[DtoPath, DtoAttribute]
        for (clazz <- dtos) {
            for (attr <- getDtoAttributes(clazz)) {
                map += attr.path -> attr
            }
        }
        map.toMap
    }

    private def getDtoAttributes(clazz: DtoClass): Seq[DtoAttribute] = {
        val resources =
            Option(getAnnotation(clazz, classOf[Resource])).toSeq ++
            Option(getAnnotation(clazz, classOf[Resources])).map(_.value())
                .getOrElse(Array[Resource]()).toSeq
        resources.map(r => DtoAttribute(
            getDtoPath(r), clazz, r,
            getAnnotation(clazz, classOf[ZoomClass]),
            getDtoField(clazz, classOf[ResourceId]),
            getDtoField(clazz, classOf[ParentId]),
            getDtoField(clazz, classOf[Ownership]),
            getDtoSubresources(clazz)))
    }

    private def getDtoPath(resource: Resource): DtoPath = {
        var r = resource
        var list = List[String]()
        while (r ne null) {
            list = r.name() +: list
            r = if (r.parent() ne null)
                r.parent().getAnnotation(classOf[Resource])
            else null
        }
        list
    }

    private def getDtoField(clazz: DtoClass,
                            annotationClass: Class[_ <: Annotation])
    : FieldAttribute = {
        var c: Class[_] = clazz
        while (classOf[UriResource] isAssignableFrom c) {
            for (field <- c.getDeclaredFields) {
                if (field.getAnnotation(annotationClass) ne null) {
                    return FieldAttribute(
                        field, field.getAnnotation(classOf[Subresource]),
                        field.getAnnotation(classOf[ZoomField]))
                }
            }
            c = c.getSuperclass
        }
        null
    }

    private def getDtoSubresources(clazz: DtoClass)
    : Map[String, FieldAttribute] = {
        var c: Class[_] = clazz
        val subresources = new mutable.HashMap[String, FieldAttribute]
        while (classOf[UriResource] isAssignableFrom c) {
            for (field <- c.getDeclaredFields) {
                val subresource = field.getAnnotation(classOf[Subresource])
                if (subresource ne null) {
                    subresources += subresource.name -> FieldAttribute(
                        field, subresource,
                        field.getAnnotation(classOf[ZoomField]))
                }
            }
            c = c.getSuperclass
        }
        subresources.toMap
    }

    private def getAnnotation[T >: Null <: Annotation]
                             (clazz: DtoClass, annotationClass: Class[T]): T = {
        var c: Class[_] = clazz
        while (classOf[UriResource] isAssignableFrom c) {
            val annotation = c.getAnnotation(annotationClass)
            if (annotation != null) return annotation
            else c = c.getSuperclass
        }
        null
    }
}
