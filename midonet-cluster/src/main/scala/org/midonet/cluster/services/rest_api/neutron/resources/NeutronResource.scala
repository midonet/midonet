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

package org.midonet.cluster.services.rest_api.neutron.resources

import java.net.URI

import javax.ws.rs.core.UriInfo
import javax.ws.rs.{GET, Path, Produces}

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation.ApiResource
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder._
import org.midonet.cluster.rest_api.neutron.models.Neutron
import org.midonet.cluster.rest_api.neutron.resources._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@ApiResource(version = 1, name = "neutron")
@Path("neutron")
@RequestScoped
class NeutronResource @Inject()(uriInfo: UriInfo,
                                resContext: ResourceContext,
                                api: NeutronZoomPlugin) {

    @Path("networks")
    def networkResource: NetworkResource = new NetworkResource(uriInfo, api)

    @Path("subnets")
    def subnetResource: SubnetResource = new SubnetResource(uriInfo, api)

    @Path("ports")
    def portResource: PortResource = new PortResource(uriInfo, api)

    @Path("routers")
    def routerResource: RouterResource = new RouterResource(uriInfo, api)

    @Path("floating_ips")
    def floatingIpResource: FloatingIpResource = new FloatingIpResource(uriInfo, api)

    @Path("security_groups")
    def securityGroupResource: SecurityGroupResource = new SecurityGroupResource(uriInfo, api)

    @Path("security_group_rules")
    def securityGroupRuleResource: SecurityGroupRuleResource = new SecurityGroupRuleResource(uriInfo, api)

    @Path("lb")
    def loadBalancerResource: LbResource = new LbResource(uriInfo, api)

    @Path("lbs_v2")
    def loadBalancerV2Resource: LbV2Resource = new LbV2Resource(uriInfo, api)

    @Path("pools_v2")
    def poolsV2Resource: PoolV2Resource = new PoolV2Resource(uriInfo, api)

    @Path("pool_members_v2")
    def poolMembersV2Resource: PoolMemberV2Resource = new PoolMemberV2Resource(uriInfo, api)

    @Path("listeners_v2")
    def listenersV2Resource: ListenerV2Resource = new ListenerV2Resource(uriInfo, api)

    @Path("health_monitors_v2")
    def healthMonitorsV2Resource: HealthMonitorV2Resource = new HealthMonitorV2Resource(uriInfo, api)

    @Path("firewalls")
    def firewallResource: FirewallResource = new FirewallResource(uriInfo, api)

    @Path("vpnservices")
    def vpnServicesResource: VpnServiceResource = new VpnServiceResource(uriInfo, api)

    @Path("ipsec_site_connections")
    def ipsecSiteConnectionResource: IpsecSiteConnectionResource =
        new IpsecSiteConnectionResource(uriInfo, api)

    @Path("l2_gateway_connections")
    def l2GatewayConnResource: L2GatewayConnectionResource =
        new L2GatewayConnectionResource(uriInfo, api)

    @Path("gateway_devices")
    def gatewayDeviceResource: GatewayDeviceResource =
        new GatewayDeviceResource(uriInfo, api)

    @Path("remote_mac_entries")
    def remoteMacEntriesResource: RemoteMacEntryResource =
        new RemoteMacEntryResource(uriInfo, api)

    @Path("bgp_speakers")
    def bgpSpeakerResource: BgpSpeakerResource =
        new BgpSpeakerResource(uriInfo, api)

    @Path("bgp_peers")
    def bgpPeerResource: BgpPeerResource =
        new BgpPeerResource(uriInfo, api)

    @Path("tap_flows")
    def tapFlowsResource: TapFlowResource =
        new TapFlowResource(uriInfo, api)

    @Path("tap_services")
    def tapServicesResource: TapServiceResource =
        new TapServiceResource(uriInfo, api)

    @Path("firewall_logs")
    def firewallLogResource: FirewallLogResource =
        new FirewallLogResource(uriInfo, api)

    @Path("logging_resources")
    def loggingResourceResource: LoggingResourceResource=
        new LoggingResourceResource(uriInfo, api)

    @GET
    @Produces(Array(MidonetMediaTypes.NEUTRON_JSON_V3)) def get: Neutron = {
        val neutron: Neutron = new Neutron
        val baseUri: URI = uriInfo.getBaseUri
        neutron.uri = getNeutron(baseUri)
        neutron.networks = getNetworks(baseUri)
        neutron.networkTemplate = getNetworkTemplate(baseUri)
        neutron.subnets = getSubnets(baseUri)
        neutron.subnetTemplate = getSubnetTemplate(baseUri)
        neutron.ports = getPorts(baseUri)
        neutron.portTemplate = getPortTemplate(baseUri)
        neutron.routers = getRouters(baseUri)
        neutron.routerTemplate = getRouterTemplate(baseUri)
        neutron.addRouterInterfaceTemplate =
            getAddRouterInterfaceTemplate(baseUri)
        neutron.removeRouterInterfaceTemplate =
            getRemoveRouterInterfaceTemplate(baseUri)
        neutron.floatingIps = getFloatingIps(baseUri)
        neutron.floatingIpTemplate = getFloatingIpTemplate(baseUri)
        neutron.securityGroups = getSecurityGroups(baseUri)
        neutron.securityGroupTemplate = getSecurityGroupTemplate(baseUri)
        neutron.securityGroupRules = getSecurityGroupRules(baseUri)
        neutron.securityGroupRuleTemplate =
            getSecurityGroupRuleTemplate(baseUri)
        neutron.loadBalancer = LbResource.buildLoadBalancer(baseUri)
        neutron.loadBalancersV2 = getLoadBalancersV2(baseUri)
        neutron.poolsV2 = getPoolsV2(baseUri)
        neutron.poolMembersV2 = getPoolMembersV2(baseUri)
        neutron.listenersV2 = getListenersV2(baseUri)
        neutron.healthMonitorsV2 = getHealthMonitorsV2(baseUri)
        neutron.firewalls = getFirewalls(baseUri)
        neutron.firewallTemplate = getFirewallTemplate(baseUri)
        neutron.vpnServices = getVpnServices(baseUri)
        neutron.vpnServiceTemplate = getVpnServiceTemplate(baseUri)
        neutron.ipsecSiteConnections = getIpsecSiteConnections(baseUri)
        neutron.ipsecSiteConnectionTemplate =
            getIpsecSiteConnectionTemplate(baseUri)
        neutron.l2GatewayConns = getL2GatewayConns(baseUri)
        neutron.l2GatewayConnTemplate = getL2GatewayConnTemplate(baseUri)
        neutron.gatewayDevices = getGatewayDevices(baseUri)
        neutron.gatewayDeviceTemplate = getGatewayDeviceTemplate(baseUri)
        neutron.remoteMacEntries = getRemoteMacEntries(baseUri)
        neutron.remoteMacEntryTemplate = getRemoteMacEntryTemplate(baseUri)
        neutron.bgpSpeakers = getBgpSpeakers(baseUri)
        neutron.bgpSpeakerTemplate = getBgpSpeakerTemplate(baseUri)
        neutron.bgpPeers = getBgpPeers(baseUri)
        neutron.bgpPeerTemplate = getBgpPeerTemplate(baseUri)
        neutron.tapFlows = getTapFlows(baseUri)
        neutron.tapFlowTemplate = getTapFlowTemplate(baseUri)
        neutron.tapServices = getTapServices(baseUri)
        neutron.tapServiceTemplate = getTapServiceTemplate(baseUri)
        neutron.firewallLogs = getFirewallLogs(baseUri)
        neutron.firewallLogTemplate = getFirewallLogTemplate(baseUri)
        neutron.loggingResources = getLoggingResources(baseUri)
        neutron.loggingResourceTemplate= getLoggingResourceTemplate(baseUri)
        neutron
    }
}
