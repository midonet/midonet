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
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.midonet.cluster.Client;
import org.midonet.cluster.data.dhcp.Opt121;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.cluster.data.dhcp.Subnet6;
import org.midonet.cluster.data.dhcp.V6Host;
import org.midonet.cluster.data.host.Host;
import org.midonet.cluster.data.host.Interface;
import org.midonet.cluster.data.host.VirtualPortMapping;
import org.midonet.cluster.data.l4lb.HealthMonitor;
import org.midonet.cluster.data.l4lb.LoadBalancer;
import org.midonet.cluster.data.l4lb.Pool;
import org.midonet.cluster.data.l4lb.PoolMember;
import org.midonet.cluster.data.l4lb.VIP;
import org.midonet.cluster.data.neutron.ExtraDhcpOpt;
import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.RouterPort;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.cluster.data.rules.ForwardNatRule;
import org.midonet.cluster.data.rules.JumpRule;
import org.midonet.cluster.data.rules.LiteralRule;
import org.midonet.cluster.data.rules.TraceRule;
import org.midonet.cluster.data.rules.ReverseNatRule;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.PortDirectory.BridgePortConfig;
import org.midonet.midolman.state.PortDirectory.RouterPortConfig;
import org.midonet.midolman.state.PortDirectory.VxLanPortConfig;
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

import static org.midonet.midolman.layer3.Route.NO_GATEWAY;


/**
 * Temporary class that defines methods to convert to/from DTOs used in
 * ZkManager classes and those in cluster.
 */
public class Converter {

    public static List<org.midonet.cluster.data.dhcp.ExtraDhcpOpt>
        toExtraDhcpOptsList(List<ExtraDhcpOpt> opts) {

        List<org.midonet.cluster.data.dhcp.ExtraDhcpOpt> retOpts =
            new ArrayList<>();
        for (ExtraDhcpOpt opt : opts) {
            retOpts.add(new org.midonet.cluster.data.dhcp.ExtraDhcpOpt(
                opt.optName, opt.optValue));
        }

        return retOpts;
    }

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
        bridgeConfig.disableAntiSpoof = bridge.getDisableAntiSpoof();
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
                .setDisableAntiSpoof(bridge.disableAntiSpoof)
                .setProperties(bridge.properties);
    }

    public static ChainConfig toChainConfig(Chain chain) {
        ChainConfig chainConfig = new ChainConfig(chain.getName());
        chainConfig.properties = new HashMap<>(
                chain.getProperties());

        return chainConfig;
    }

    public static Chain fromChainConfig(ChainConfig chain) {
        if (chain == null)
            return null;

        return new Chain()
                .setName(chain.name)
                .setProperties(chain.properties);
    }

    public static PortGroupConfig toPortGroupConfig(PortGroup portGroup) {
        PortGroupConfig portGroupConfig = new PortGroupConfig();

        portGroupConfig.name = portGroup.getData().name;
        portGroupConfig.stateful = portGroup.getData().stateful;
        portGroupConfig.properties = new HashMap<>(
                portGroup.getData().properties);

        return portGroupConfig;
    }

    public static PortGroup fromPortGroupConfig(PortGroupConfig portGroup) {
        if (portGroup == null)
            return null;

        return new PortGroup()
                .setName(portGroup.name)
                .setProperties(portGroup.properties)
                .setStateful(portGroup.stateful);
    }

    public static IpAddrGroupConfig toIpAddrGroupConfig(IpAddrGroup group) {
        IpAddrGroupConfig config = new IpAddrGroupConfig();

        config.name = group.getData().name;
        config.id = group.getId();
        config.properties = new HashMap<>(
                group.getData().properties);

        return config;
    }

    public static IpAddrGroup fromIpAddrGroupConfig(IpAddrGroupConfig group) {
        if (group == null)
            return null;

        return new IpAddrGroup()
                .setName(group.name)
                .setProperties(group.properties);
    }

    public static LoadBalancerConfig toLoadBalancerConfig(
            LoadBalancer loadBalancer) {
        LoadBalancerConfig loadBalancerConfig = new LoadBalancerConfig();
        loadBalancerConfig.routerId = loadBalancer.getRouterId();
        loadBalancerConfig.adminStateUp = loadBalancer.isAdminStateUp();
        return loadBalancerConfig;
    }

    public static LoadBalancer fromLoadBalancerConfig(
            LoadBalancerConfig loadBalancerConfig) {
        return new LoadBalancer()
                .setRouterId(loadBalancerConfig.routerId)
                .setAdminStateUp(loadBalancerConfig.adminStateUp);
    }

    public static HealthMonitorConfig toHealthMonitorConfig(
            HealthMonitor healthMonitor) {
        HealthMonitorConfig healthMonitorConfig = new HealthMonitorConfig();
        healthMonitorConfig.type = healthMonitor.getType();
        healthMonitorConfig.delay = healthMonitor.getDelay();
        healthMonitorConfig.adminStateUp = healthMonitor.isAdminStateUp();
        healthMonitorConfig.maxRetries = healthMonitor.getMaxRetries();
        healthMonitorConfig.timeout = healthMonitor.getTimeout();
        healthMonitorConfig.status = healthMonitor.getStatus();
        return healthMonitorConfig;
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

    public static PoolMemberConfig toPoolMemberConfig(PoolMember poolMember) {
        PoolMemberConfig poolMemberConfig = new PoolMemberConfig();
        poolMemberConfig.poolId = poolMember.getPoolId();
        poolMemberConfig.address = poolMember.getAddress();
        poolMemberConfig.protocolPort = poolMember.getProtocolPort();
        poolMemberConfig.weight = poolMember.getWeight();
        poolMemberConfig.adminStateUp = poolMember.getAdminStateUp();
        poolMemberConfig.status = poolMember.getStatus();
        return poolMemberConfig;
    }

    public static PoolMember fromPoolMemberConfig(PoolMemberConfig poolMemberConfig) {
        return new PoolMember().setPoolId(poolMemberConfig.poolId)
                               .setAddress(poolMemberConfig.address)
                               .setProtocolPort(poolMemberConfig.protocolPort)
                               .setWeight(poolMemberConfig.weight)
                               .setAdminStateUp(poolMemberConfig.adminStateUp)
                               .setStatus(poolMemberConfig.status);
    }

    public static PoolConfig toPoolConfig(Pool pool) {
        PoolConfig poolConfig = new PoolConfig();
        poolConfig.loadBalancerId = pool.getLoadBalancerId();
        poolConfig.healthMonitorId = pool.getHealthMonitorId();
        poolConfig.protocol = pool.getProtocol();
        poolConfig.lbMethod = pool.getLbMethod();
        poolConfig.adminStateUp = pool.isAdminStateUp();
        poolConfig.status = pool.getStatus();
        poolConfig.mappingStatus = pool.getMappingStatus();
        return poolConfig;
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

    public static VipConfig toVipConfig(VIP vip) {
        VipConfig vipConfig = new VipConfig();
        vipConfig.loadBalancerId = vip.getLoadBalancerId();
        vipConfig.poolId = vip.getPoolId();
        vipConfig.address = vip.getAddress();
        vipConfig.protocolPort = vip.getProtocolPort();
        vipConfig.sessionPersistence = vip.getSessionPersistence();
        vipConfig.adminStateUp = vip.getAdminStateUp();

        return vipConfig;
    }

    public static VIP fromVipConfig(VipConfig vipConfig) {
        return new VIP().setLoadBalancerId(vipConfig.loadBalancerId)
                        .setPoolId(vipConfig.poolId)
                        .setAddress(vipConfig.address)
                        .setProtocolPort(vipConfig.protocolPort)
                        .setSessionPersistence(vipConfig.sessionPersistence)
                        .setAdminStateUp(vipConfig.adminStateUp);
    }

    public static PortConfig toPortConfig(Port<?,?> port) {

        PortConfig portConfig = null;
        if (port instanceof BridgePort) {
            BridgePort typedPort = (BridgePort) port;
            BridgePortConfig typedPortConfig = new BridgePortConfig();
            typedPortConfig.setVlanId(typedPort.getVlanId());

            if(typedPort.getProperty(Port.Property.v1PortType) != null) {
                if(typedPort.getProperty(Port.Property.v1PortType)
                        .equals(Client.PortType.ExteriorBridge.toString())) {
                    typedPortConfig.setV1ApiType("ExteriorBridgePort");
                } else if (typedPort.getProperty(Port.Property.v1PortType)
                        .equals(Client.PortType.InteriorBridge.toString())) {
                    typedPortConfig.setV1ApiType("InteriorBridgePort");
                }
            }
            portConfig = typedPortConfig;
        } else if (port instanceof RouterPort) {
            RouterPort typedPort = (RouterPort) port;
            RouterPortConfig routerPortConfig = new RouterPortConfig();
            routerPortConfig.setBgps(typedPort.getBgps());
            routerPortConfig.setHwAddr(typedPort.getHwAddr());
            routerPortConfig.setPortAddr(typedPort.getPortAddr());
            routerPortConfig.setNwAddr(typedPort.getNwAddr());
            routerPortConfig.nwLength = typedPort.getNwLength();

            if(typedPort.getProperty(Port.Property.v1PortType) != null) {
                if(typedPort.getProperty(Port.Property.v1PortType)
                        .equals(Client.PortType.ExteriorRouter.toString())) {
                    routerPortConfig.setV1ApiType("ExteriorRouterPort");
                } else if (typedPort.getProperty(Port.Property.v1PortType)
                        .equals(Client.PortType.InteriorRouter.toString())) {
                    routerPortConfig.setV1ApiType("InteriorRouterPort");
                }
            }

            portConfig = routerPortConfig;
        } else if (port instanceof VxLanPort) {
            VxLanPort typedPort = (VxLanPort)port;
            VxLanPortConfig typedConfig = new VxLanPortConfig();
            typedConfig.setMgmtIpAddr(typedPort.getMgmtIpAddr().toString());
            typedConfig.setMgmtPort(typedPort.getMgmtPort());
            typedConfig.setVni(typedPort.getVni());
            typedConfig.setTunIpAddr(typedPort.getTunnelIp().toString());
            typedConfig.setTunnelZoneId(typedPort.getTunnelZoneId());
            portConfig = typedConfig;
        }

        if (portConfig == null)
            return null;

        portConfig.id = port.getId();
        portConfig.device_id = port.getDeviceId();
        portConfig.adminStateUp = port.isAdminStateUp();
        portConfig.peerId = port.getPeerId();
        portConfig.hostId = port.getHostId();
        portConfig.interfaceName = port.getInterfaceName();
        portConfig.inboundFilter = port.getInboundFilter();
        portConfig.outboundFilter = port.getOutboundFilter();
        portConfig.tunnelKey = port.getTunnelKey();
        portConfig.properties = port.getProperties();
        portConfig.portGroupIDs = port.getPortGroups();

        return portConfig;
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

    public static RouterConfig toRouterConfig(Router router) {
        RouterConfig routerConfig = new RouterConfig();

        routerConfig.name = router.getName();
        routerConfig.adminStateUp = router.isAdminStateUp();
        routerConfig.inboundFilter = router.getInboundFilter();
        routerConfig.outboundFilter = router.getOutboundFilter();
        routerConfig.loadBalancer = router.getLoadBalancer();
        routerConfig.properties = new HashMap<>(router.getProperties());

        return routerConfig;
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

    public static org.midonet.midolman.layer3.Route toRouteConfig(
            Route route) {

        int gateway = route.getNextHopGateway() == null ? NO_GATEWAY :
                      IPv4Addr.stringToInt(route.getNextHopGateway());
        return new org.midonet.midolman.layer3.Route(
            IPv4Addr.stringToInt(route.getSrcNetworkAddr()),
            route.getSrcNetworkLength(),
            IPv4Addr.stringToInt(route.getDstNetworkAddr()),
            route.getDstNetworkLength(),
            route.getNextHop(),
            route.getNextHopPort(),
            gateway,
            route.getWeight(),
            route.getAttributes(),
            route.getRouterId(),
            route.isLearned()
        );
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

    public static org.midonet.midolman.rules.Rule toRuleConfig(Rule<?,?> rule) {

        org.midonet.midolman.rules.Rule ruleConfig = null;
        if (rule instanceof LiteralRule) {
            LiteralRule typedRule = (LiteralRule) rule;

            ruleConfig = new org.midonet.midolman.rules.LiteralRule(
                    typedRule.getCondition(),
                    typedRule.getAction()
            );
        }

        if (rule instanceof TraceRule) {
            TraceRule typedRule = (TraceRule) rule;
            ruleConfig = new org.midonet.midolman.rules.TraceRule(
                    typedRule.getRequestId(), typedRule.getCondition(),
                    typedRule.getLimit());
        }

        if (rule instanceof JumpRule) {
            JumpRule typedRule = (JumpRule) rule;

            ruleConfig = new org.midonet.midolman.rules.JumpRule(
                    typedRule.getCondition(),
                    typedRule.getJumpToChainId(),
                    typedRule.getJumpToChainName()
            );
        }

        if (rule instanceof ForwardNatRule) {
            ForwardNatRule typedRule = (ForwardNatRule) rule;

            ruleConfig = new org.midonet.midolman.rules.ForwardNatRule(
                    typedRule.getCondition(),
                    typedRule.getAction(),
                    typedRule.getChainId(),
                    typedRule.getPosition(),
                    typedRule.isDnat(),
                    typedRule.getTargets()
            );
        }

        if (rule instanceof ReverseNatRule) {
            ReverseNatRule typedRule = (ReverseNatRule) rule;

            ruleConfig = new org.midonet.midolman.rules.ReverseNatRule(
                    typedRule.getCondition(),
                    typedRule.getAction(),
                    typedRule.isDnat()
            );

        }

        if (ruleConfig != null) {
            ruleConfig.chainId = rule.getChainId();
            ruleConfig.setMeterName(rule.getMeterName());
            ruleConfig.setProperties(rule.getProperties());
        }

        return ruleConfig;
    }

    public static Rule<?,?> fromRuleConfig(
            org.midonet.midolman.rules.Rule ruleConfig) {

        Rule<?,?> rule = null;

        if (ruleConfig instanceof org.midonet.midolman.rules.LiteralRule) {
            rule = new LiteralRule(ruleConfig.getCondition(),
                    ruleConfig.action);
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

    public static BridgeDhcpZkManager.Host toDhcpHostConfig(
            org.midonet.cluster.data.dhcp.Host host) {

        return new BridgeDhcpZkManager.Host(host.getMAC(), host.getIp(),
                host.getName(), host.getExtraDhcpOpts());

    }

    public static org.midonet.cluster.data.dhcp.Host fromDhcpHostConfig
            (BridgeDhcpZkManager.Host hostConfig) {
        return new org.midonet.cluster.data.dhcp.Host()
                .setIp(hostConfig.getIp())
                .setMAC(hostConfig.getMac())
                .setName(hostConfig.getName())
                .setExtraDhcpOpts(hostConfig.getExtraDhcpOpts());
    }

    public static BridgeDhcpZkManager.Subnet toDhcpSubnetConfig(
            Subnet subnet) {

        List<BridgeDhcpZkManager.Opt121> opt121Configs = new ArrayList<>();
        if (subnet.getOpt121Routes() != null) {
            for (Opt121 opt121 : subnet.getOpt121Routes()) {
                opt121Configs.add(toDhcpOpt121Config(opt121));
            }
        }

        // If isEnabled is not set, default to enabled
        boolean enabled = (subnet.isEnabled() == null || subnet.isEnabled());

        return new BridgeDhcpZkManager.Subnet(
                      subnet.getSubnetAddr(),
                      subnet.getDefaultGateway(),
                      subnet.getServerAddr(),
                      subnet.getDnsServerAddrs(),
                      subnet.getInterfaceMTU(),
                      opt121Configs, enabled);
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

    public static BridgeDhcpZkManager.Opt121 toDhcpOpt121Config(Opt121 opt121) {
        return new BridgeDhcpZkManager.Opt121(opt121.getRtDstSubnet(),
                                              opt121.getGateway());
    }

    public static Opt121 fromDhcpOpt121Config(BridgeDhcpZkManager.Opt121
                                              opt121Config) {
        return new Opt121().setGateway(opt121Config.getGateway())
                           .setRtDstSubnet(opt121Config.getRtDstSubnet());
    }

    public static BridgeDhcpV6ZkManager.Host toDhcpV6HostConfig(
            V6Host host) {

        return new BridgeDhcpV6ZkManager.Host(host.getClientId(),
                                              host.getFixedAddress(),
                                              host.getName());
    }

    public static V6Host fromDhcpV6HostConfig
            (BridgeDhcpV6ZkManager.Host hostConfig) {
        return new V6Host()
                .setFixedAddress(hostConfig.getFixedAddress())
                .setClientId(hostConfig.getClientId())
                .setName(hostConfig.getName());
    }

    public static BridgeDhcpV6ZkManager.Subnet6 toDhcpSubnet6Config(
            Subnet6 subnet) {

        return new BridgeDhcpV6ZkManager.Subnet6(subnet.getPrefix());
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

    public static HostDirectory.Metadata toHostConfig(Host host) {
        HostDirectory.Metadata metadata = new HostDirectory.Metadata();

        metadata.setName(host.getName());
        metadata.setAddresses(host.getAddresses());
        metadata.setTunnelZones(new HashSet<UUID>(host.getTunnelZones()));

        return metadata;
    }

    public static VirtualPortMapping fromHostVirtPortMappingConfig(
            HostDirectory.VirtualPortMapping mappingConfig) {

        return new VirtualPortMapping()
                .setLocalDeviceName(mappingConfig.getLocalDeviceName())
                .setVirtualPortId(mappingConfig.getVirtualPortId());
    }

    public static VTEP fromVtepConfig(VtepZkManager.VtepConfig config) {
        VTEP vtep = new VTEP();
        vtep.setMgmtPort(config.mgmtPort);
        vtep.setTunnelZone(config.tunnelZone);
        return vtep;
    }

    public static VtepZkManager.VtepConfig toVtepConfig(VTEP vtep) {
        VtepZkManager.VtepConfig vtepConfig = new VtepZkManager.VtepConfig();
        vtepConfig.mgmtPort = vtep.getMgmtPort();
        vtepConfig.tunnelZone = vtep.getTunnelZoneId();
        return vtepConfig;
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

    public static TraceRequestConfig toTraceRequestConfig(
            TraceRequest traceRequest) {
        TraceRequestConfig config = new TraceRequestConfig();
        config.name = traceRequest.getName();
        config.deviceType = traceRequest.getDeviceType();
        config.deviceId = traceRequest.getDeviceId();
        config.condition = traceRequest.getCondition();
        config.creationTimestampMs = traceRequest.getCreationTimestampMs();
        config.limit = traceRequest.getLimit();
        config.enabledRule = traceRequest.getEnabledRule();
        return config;
    }
}
