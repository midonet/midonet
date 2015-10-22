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
package org.midonet.cluster.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.midonet.cluster.Client;
import org.midonet.cluster.data.dhcp.ExtraDhcpOpt;
import org.midonet.cluster.data.dhcp.Opt121;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.cluster.data.dhcp.Subnet6;
import org.midonet.cluster.data.dhcp.V6Host;
import org.midonet.cluster.data.host.Host;
import org.midonet.cluster.data.host.Interface;
import org.midonet.cluster.data.l4lb.HealthMonitor;
import org.midonet.cluster.data.l4lb.LoadBalancer;
import org.midonet.cluster.data.l4lb.Pool;
import org.midonet.cluster.data.l4lb.PoolMember;
import org.midonet.cluster.data.l4lb.VIP;
import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.RouterPort;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.cluster.data.rules.ForwardNatRule;
import org.midonet.cluster.data.rules.JumpRule;
import org.midonet.cluster.data.rules.LiteralRule;
import org.midonet.cluster.data.rules.ReverseNatRule;
import org.midonet.cluster.data.rules.TraceRule;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.PortDirectory.BridgePortConfig;
import org.midonet.midolman.state.PortDirectory.RouterPortConfig;
import org.midonet.midolman.state.PortDirectory.VxLanPortConfig;
import org.midonet.midolman.state.zkManagers.AdRouteZkManager;
import org.midonet.midolman.state.zkManagers.AdRouteZkManager.AdRouteConfig;
import org.midonet.midolman.state.zkManagers.BridgeDhcpV6ZkManager;
import org.midonet.midolman.state.zkManagers.BridgeDhcpZkManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager.BridgeConfig;
import org.midonet.midolman.state.zkManagers.ChainZkManager.ChainConfig;
import org.midonet.midolman.state.zkManagers.HealthMonitorZkManager.HealthMonitorConfig;
import org.midonet.midolman.state.zkManagers.IpAddrGroupZkManager.IpAddrGroupConfig;
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager.LoadBalancerConfig;
import org.midonet.midolman.state.zkManagers.PoolMemberZkManager.PoolMemberConfig;
import org.midonet.midolman.state.zkManagers.PoolZkManager.PoolConfig;
import org.midonet.midolman.state.zkManagers.PortGroupZkManager.PortGroupConfig;
import org.midonet.midolman.state.zkManagers.RouterZkManager.RouterConfig;
import org.midonet.midolman.state.zkManagers.TraceRequestZkManager.TraceRequestConfig;
import org.midonet.midolman.state.zkManagers.VipZkManager.VipConfig;
import org.midonet.midolman.state.zkManagers.VtepZkManager;
import org.midonet.packets.IPv4Addr;


/**
 * Temporary class that defines methods to convert to/from DTOs used in
 * ZkManager classes and those in cluster.
 */
public class Converter {

    public static AdRouteConfig toAdRouteConfig(
            AdRoute adRoute) {

        return new AdRouteConfig(adRoute.getBgpId(), adRoute.getNwPrefix(),
                adRoute.getPrefixLength());

    }

    public static AdRoute fromAdRouteConfig(AdRouteConfig adRouteConfig) {

        return new AdRoute()
                .setBgpId(adRouteConfig.bgpId)
                .setNwPrefix(adRouteConfig.nwPrefix)
                .setPrefixLength(adRouteConfig.prefixLength);

    }

    public static BridgeConfig toBridgeConfig(Bridge bridge) {
        BridgeConfig bridgeConfig = new BridgeConfig();

        bridgeConfig.name = bridge.getName();
        bridgeConfig.adminStateUp = bridge.isAdminStateUp();
        bridgeConfig.inboundFilter = bridge.getInboundFilter();
        bridgeConfig.outboundFilter = bridge.getOutboundFilter();
        bridgeConfig.vxLanPortId = bridge.getVxLanPortId();
        bridgeConfig.vxLanPortIds = bridge.getVxLanPortIds();
        bridgeConfig.tunnelKey = bridge.getTunnelKey();
        bridgeConfig.properties = new HashMap<>(bridge.getProperties());

        return bridgeConfig;
    }

    public static Bridge fromBridgeConfig(BridgeConfig bridge) {
        if (bridge == null)
            return null;

        return new Bridge()
                .setName(bridge.name)
                .setAdminStateUp(bridge.adminStateUp)
                .setTunnelKey(bridge.tunnelKey)
                .setInboundFilter(bridge.inboundFilter)
                .setOutboundFilter(bridge.outboundFilter)
                .setVxLanPortId(bridge.vxLanPortId)
                .setVxLanPortIds(bridge.vxLanPortIds)
                .setProperties(bridge.properties);
    }

    public static Chain fromChainConfig(ChainConfig chain) {
        if (chain == null)
            return null;

        return new Chain()
                .setName(chain.name)
                .setProperties(chain.properties);
    }

    public static PortGroup fromPortGroupConfig(PortGroupConfig portGroup) {
        if (portGroup == null)
            return null;

        return new PortGroup()
                .setName(portGroup.name)
                .setProperties(portGroup.properties)
                .setStateful(portGroup.stateful);
    }

    public static IpAddrGroup fromIpAddrGroupConfig(IpAddrGroupConfig group) {
        if (group == null)
            return null;

        return new IpAddrGroup()
                .setName(group.name)
                .setProperties(group.properties);
    }

    public static LoadBalancer fromLoadBalancerConfig(
            LoadBalancerConfig loadBalancerConfig) {
        return new LoadBalancer()
                .setRouterId(loadBalancerConfig.routerId)
                .setAdminStateUp(loadBalancerConfig.adminStateUp);
    }

    public static HealthMonitor fromHealthMonitorConfig(
            HealthMonitorConfig healthMonitorConfig) {
        return new HealthMonitor()
                .setAdminStateUp(healthMonitorConfig.adminStateUp)
                .setDelay(healthMonitorConfig.delay)
                .setMaxRetries(healthMonitorConfig.maxRetries)
                .setTimeout(healthMonitorConfig.timeout)
                .setType(healthMonitorConfig.type)
                .setStatus(healthMonitorConfig.status);
    }

    public static PoolMember fromPoolMemberConfig(PoolMemberConfig poolMemberConfig) {
        return new PoolMember().setPoolId(poolMemberConfig.poolId)
                               .setAddress(poolMemberConfig.address)
                               .setProtocolPort(poolMemberConfig.protocolPort)
                               .setWeight(poolMemberConfig.weight)
                               .setAdminStateUp(poolMemberConfig.adminStateUp)
                               .setStatus(poolMemberConfig.status);
    }

    public static Pool fromPoolConfig(PoolConfig poolConfig) {
        return new Pool().setLoadBalancerId(poolConfig.loadBalancerId)
                         .setHealthMonitorId(poolConfig.healthMonitorId)
                         .setProtocol(poolConfig.protocol)
                         .setLbMethod(poolConfig.lbMethod)
                         .setAdminStateUp(poolConfig.adminStateUp)
                         .setStatus(poolConfig.status)
                         .setMappingStatus(poolConfig.mappingStatus);
    }

    public static VIP fromVipConfig(VipConfig vipConfig) {
        return new VIP().setLoadBalancerId(vipConfig.loadBalancerId)
                        .setPoolId(vipConfig.poolId)
                        .setAddress(vipConfig.address)
                        .setProtocolPort(vipConfig.protocolPort)
                        .setSessionPersistence(vipConfig.sessionPersistence)
                        .setAdminStateUp(vipConfig.adminStateUp);
    }

    public static Port<?,?> fromPortConfig(PortConfig portConfig) {

        Port<?,?> port = null;

        if (portConfig instanceof BridgePortConfig) {
            BridgePortConfig bridgePortConfig =
                    (BridgePortConfig) portConfig;
            BridgePort bridgePort = new BridgePort();
            bridgePort.setVlanId(bridgePortConfig.getVlanId());
            if(portConfig.isExterior()
                    || (portConfig.getV1ApiType() != null
                        && portConfig.getV1ApiType()
                           .equals("ExteriorBridgePort")))
                bridgePort.setProperty(Port.Property.v1PortType,
                        Client.PortType.ExteriorBridge.toString());
            else
                bridgePort.setProperty(Port.Property.v1PortType,
                        Client.PortType.InteriorBridge.toString());
            port = bridgePort;
        }

        if (portConfig instanceof RouterPortConfig) {
            RouterPortConfig routerPortConfig =
                    (RouterPortConfig) portConfig;

            port = new RouterPort()
                    .setNwAddr(routerPortConfig.getNwAddr())
                    .setNwLength(routerPortConfig.nwLength)
                    .setPortAddr(routerPortConfig.getPortAddr())
                    .setHwAddr(routerPortConfig.getHwAddr());
            if(port.isExterior()
                    || (portConfig.getV1ApiType() != null
                        && portConfig.getV1ApiType()
                           .equals("ExteriorRouterPort"))) {
                port.setProperty(Port.Property.v1PortType,
                        Client.PortType.ExteriorRouter.toString());
            } else
                port.setProperty(Port.Property.v1PortType,
                        Client.PortType.InteriorRouter.toString());
        }

        if (portConfig instanceof VxLanPortConfig) {
            VxLanPortConfig vxLanPortConfig =
                    (VxLanPortConfig)portConfig;

            port = new VxLanPort()
                    .setMgmtIpAddr(
                        IPv4Addr.fromString(vxLanPortConfig.getMgmtIpAddr()))
                    .setMgmtPort(vxLanPortConfig.getMgmtPort())
                    .setVni(vxLanPortConfig.getVni())
                    .setTunnelIp(
                        IPv4Addr.fromString(vxLanPortConfig.getTunIpAddr()))
                    .setTunnelZoneId(vxLanPortConfig.getTunnelZoneId());
        }

        if (port == null)
            return null;

        return port
                .setAdminStateUp(portConfig.adminStateUp)
                .setDeviceId(portConfig.device_id)
                .setHostId(portConfig.hostId)
                .setInterfaceName(portConfig.interfaceName)
                .setPeerId(portConfig.peerId)
                .setTunnelKey(portConfig.tunnelKey)
                .setInboundFilter(portConfig.inboundFilter)
                .setOutboundFilter(portConfig.outboundFilter)
                .setProperties(portConfig.properties)
                .setPortGroups(portConfig.portGroupIDs);
    }

    public static Router fromRouterConfig(RouterConfig router) {
        if (router == null)
            return null;

        return new Router()
                .setName(router.name)
                .setAdminStateUp(router.adminStateUp)
                .setInboundFilter(router.inboundFilter)
                .setOutboundFilter(router.outboundFilter)
                .setLoadBalancer(router.loadBalancer)
                .setProperties(router.properties);
    }

    public static Route fromRouteConfig(
            org.midonet.midolman.layer3.Route route) {

        if (route == null)
            return null;

        return new Route()
                .setSrcNetworkAddr(route.getSrcNetworkAddr())
                .setSrcNetworkLength(route.srcNetworkLength)
                .setDstNetworkAddr(route.getDstNetworkAddr())
                .setDstNetworkLength(route.dstNetworkLength)
                .setNextHop(route.nextHop)
                .setNextHopPort(route.nextHopPort)
                .setNextHopGateway(route.getNextHopGateway())
                .setWeight(route.weight)
                .setAttributes(route.attributes)
                .setRouterId(route.routerId)
                .setLearned(route.isLearned())
        ;
    }

    public static Rule<?,?> fromRuleConfig(
            org.midonet.midolman.rules.Rule ruleConfig) {

        Rule<?,?> rule = null;

        if (ruleConfig instanceof org.midonet.midolman.rules.LiteralRule) {
            rule = new LiteralRule(ruleConfig.getCondition())
                       .setAction(ruleConfig.action);
        }

        if (ruleConfig instanceof org.midonet.midolman.rules.TraceRule) {
            org.midonet.midolman.rules.TraceRule casted
                = (org.midonet.midolman.rules.TraceRule)ruleConfig;
            rule = new TraceRule(casted.getRequestId(),
                                 ruleConfig.getCondition(),
                                 casted.getLimit());
        }

        if (ruleConfig instanceof org.midonet.midolman.rules.JumpRule) {
            org.midonet.midolman.rules.JumpRule jumpRuleConfig =
                    (org.midonet.midolman.rules.JumpRule) ruleConfig;
            rule = new JumpRule(jumpRuleConfig.getCondition())
                    .setJumpToChainId(jumpRuleConfig.jumpToChainID)
                    .setJumpToChainName(jumpRuleConfig.jumpToChainName);
        }

        if (ruleConfig instanceof org.midonet.midolman.rules.ForwardNatRule) {
            org.midonet.midolman.rules.ForwardNatRule forwardNatRuleConfig =
                    (org.midonet.midolman.rules.ForwardNatRule) ruleConfig;

            rule = new ForwardNatRule(
                    forwardNatRuleConfig.getCondition(),
                    forwardNatRuleConfig.action,
                    forwardNatRuleConfig.getNatTargets(),
                    forwardNatRuleConfig.dnat);
        }

        if (ruleConfig instanceof org.midonet.midolman.rules.ReverseNatRule) {
            org.midonet.midolman.rules.ReverseNatRule reverseNatRuleConfig =
                    (org.midonet.midolman.rules.ReverseNatRule) ruleConfig;

            rule = new ReverseNatRule(
                    reverseNatRuleConfig.getCondition(),
                    reverseNatRuleConfig.action,
                    reverseNatRuleConfig.dnat
            );
        }

        if (rule == null)
            return null;

        return rule
                .setChainId(ruleConfig.chainId)
                .setProperties(ruleConfig.getProperties())
                .setMeterName(ruleConfig.getMeterName());
    }

    public static org.midonet.cluster.data.dhcp.Host fromDhcpHostConfig
            (BridgeDhcpZkManager.Host hostConfig) {
        return new org.midonet.cluster.data.dhcp.Host()
                .setIp(hostConfig.getIp())
                .setMAC(hostConfig.getMac())
                .setName(hostConfig.getName())
                .setExtraDhcpOpts(hostConfig.getExtraDhcpOpts());
    }

    public static Subnet fromDhcpSubnetConfig(
            BridgeDhcpZkManager.Subnet subnetConfig) {

        List<Opt121> opt121s = new ArrayList<>();
        for (BridgeDhcpZkManager.Opt121 opt121Config
                : subnetConfig.getOpt121Routes()) {
            opt121s.add(fromDhcpOpt121Config(opt121Config));
        }

        return new Subnet()
                .setDefaultGateway(subnetConfig.getDefaultGateway())
                .setOpt121Routes(opt121s)
                .setServerAddr(subnetConfig.getServerAddr())
                .setDnsServerAddrs(subnetConfig.getDnsServerAddrs())
                .setInterfaceMTU(subnetConfig.getInterfaceMTU())
                .setSubnetAddr(subnetConfig.getSubnetAddr())
                .setEnabled(subnetConfig.isEnabled());
    }

    public static Opt121 fromDhcpOpt121Config(BridgeDhcpZkManager.Opt121
                                              opt121Config) {
        return new Opt121().setGateway(opt121Config.getGateway())
                           .setRtDstSubnet(opt121Config.getRtDstSubnet());
    }

    public static V6Host fromDhcpV6HostConfig
            (BridgeDhcpV6ZkManager.Host hostConfig) {
        return new V6Host()
                .setFixedAddress(hostConfig.getFixedAddress())
                .setClientId(hostConfig.getClientId())
                .setName(hostConfig.getName());
    }

    public static Subnet6 fromDhcpSubnet6Config(
            BridgeDhcpV6ZkManager.Subnet6 subnetConfig) {

        return new Subnet6()
                .setPrefix(subnetConfig.getPrefix());
    }

    public static Interface fromHostInterfaceConfig(
            HostDirectory.Interface interfaceConfig) {
        return new Interface()
                .setAddresses(interfaceConfig.getAddresses())
                .setEndpoint(interfaceConfig.getEndpoint())
                .setMac(interfaceConfig.getMac())
                .setMtu(interfaceConfig.getMtu())
                .setName(interfaceConfig.getName())
                .setPortType(interfaceConfig.getPortType())
                .setStatus(interfaceConfig.getStatus())
                .setType(interfaceConfig.getType());
    }

    public static Host fromHostConfig(
            HostDirectory.Metadata metadataConfig) {
        return new Host()
                .setAddresses(metadataConfig.getAddresses())
                .setName(metadataConfig.getName());
    }

    public static VTEP fromVtepConfig(VtepZkManager.VtepConfig config) {
        VTEP vtep = new VTEP();
        vtep.setMgmtPort(config.mgmtPort);
        vtep.setTunnelZone(config.tunnelZone);
        return vtep;
    }

    public static TraceRequest fromTraceRequestConfig(
            TraceRequestConfig traceRequestConfig) {
        return new TraceRequest()
            .setName(traceRequestConfig.name)
            .setDeviceType(traceRequestConfig.deviceType)
            .setDeviceId(traceRequestConfig.deviceId)
            .setCondition(traceRequestConfig.condition)
            .setCreationTimestampMs(traceRequestConfig.creationTimestampMs)
            .setLimit(traceRequestConfig.limit)
            .setEnabledRule(traceRequestConfig.enabledRule);
    }

}
