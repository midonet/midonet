/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.midonet.cluster.Client;
import org.midonet.cluster.data.Entity.TaggableEntity;
import org.midonet.cluster.data.dhcp.Opt121;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.cluster.data.dhcp.Subnet6;
import org.midonet.cluster.data.dhcp.V6Host;
import org.midonet.cluster.data.host.Command;
import org.midonet.cluster.data.host.ErrorLogItem;
import org.midonet.cluster.data.host.Host;
import org.midonet.cluster.data.host.Interface;
import org.midonet.cluster.data.host.VirtualPortMapping;
import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.RouterPort;
import org.midonet.cluster.data.rules.ForwardNatRule;
import org.midonet.cluster.data.rules.JumpRule;
import org.midonet.cluster.data.rules.LiteralRule;
import org.midonet.cluster.data.rules.ReverseNatRule;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.PortDirectory.BridgePortConfig;
import org.midonet.midolman.state.PortDirectory.RouterPortConfig;
import org.midonet.midolman.state.zkManagers.AdRouteZkManager.AdRouteConfig;
import org.midonet.midolman.state.zkManagers.BridgeDhcpV6ZkManager;
import org.midonet.midolman.state.zkManagers.BridgeDhcpZkManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager.BridgeConfig;
import org.midonet.midolman.state.zkManagers.ChainZkManager.ChainConfig;
import org.midonet.midolman.state.zkManagers.IpAddrGroupZkManager
        .IpAddrGroupConfig;
import org.midonet.midolman.state.zkManagers.PortGroupZkManager.PortGroupConfig;
import org.midonet.midolman.state.zkManagers.RouterZkManager.RouterConfig;
import org.midonet.midolman.state.zkManagers.TaggableConfig;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IntIPv4;
import org.midonet.packets.Net;


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
        bridgeConfig.tunnelKey = bridge.getTunnelKey();
        bridgeConfig.properties = new HashMap<String, String>(
                bridge.getProperties());

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
                .setProperties(bridge.properties);
    }

    public static ChainConfig toChainConfig(Chain chain) {
        ChainConfig chainConfig = new ChainConfig();

        chainConfig.name = chain.getName();
        chainConfig.properties = new HashMap<String, String>(
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
        portGroupConfig.properties = new HashMap<String, String>(
                portGroup.getData().properties);

        return portGroupConfig;
    }

    public static PortGroup fromPortGroupConfig(PortGroupConfig portGroup) {
        if (portGroup == null)
            return null;

        return new PortGroup()
                .setName(portGroup.name)
                .setProperties(portGroup.properties);
    }

    public static IpAddrGroupConfig toIpAddrGroupConfig(IpAddrGroup group) {
        IpAddrGroupConfig config = new IpAddrGroupConfig();

        config.name = group.getData().name;
        config.id = group.getId();
        config.properties = new HashMap<String, String>(
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

    public static PortConfig toPortConfig(Port port) {

        PortConfig portConfig = null;
        if (port instanceof BridgePort) {
            BridgePort typedPort = (BridgePort) port;
            BridgePortConfig typedPortConfig = new BridgePortConfig();
            typedPortConfig.setHostId(typedPort.getHostId());
            typedPortConfig.setInterfaceName(typedPort.getInterfaceName());
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
        }

        if (port instanceof RouterPort) {
            RouterPort typedPort = (RouterPort) port;
            RouterPortConfig routerPortConfig = new RouterPortConfig();
            routerPortConfig.setHostId(typedPort.getHostId());
            routerPortConfig.setInterfaceName(typedPort.getInterfaceName());
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
        }

        if (portConfig == null)
            return null;

        portConfig.device_id = port.getDeviceId();
        portConfig.adminStateUp = port.isAdminStateUp();
        portConfig.peerId = port.getPeerId();
        portConfig.inboundFilter = port.getInboundFilter();
        portConfig.outboundFilter = port.getOutboundFilter();
        portConfig.tunnelKey = port.getTunnelKey();
        portConfig.properties = port.getProperties();
        portConfig.portGroupIDs = port.getPortGroups();

        return portConfig;
    }

    public static Port fromPortConfig(PortConfig portConfig) {

        Port port = null;

        if (portConfig instanceof BridgePortConfig) {
            BridgePortConfig bridgePortConfig =
                    (BridgePortConfig) portConfig;
            BridgePort bridgePort = new BridgePort();
            bridgePort.setVlanId(bridgePortConfig.getVlanId());
            bridgePort.setHostId(bridgePortConfig.getHostId());
            bridgePort.setInterfaceName(bridgePortConfig.getInterfaceName());
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
                    .setHwAddr(routerPortConfig.getHwAddr())
                    .setHostId(routerPortConfig.getHostId())
                    .setInterfaceName(routerPortConfig.getInterfaceName());
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

        if (port == null)
            return null;

        return port
                .setDeviceId(portConfig.device_id)
                .setAdminStateUp(portConfig.adminStateUp)
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
        routerConfig.properties = new HashMap<String, String>(
                router.getProperties());

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
                .setProperties(router.properties);
    }

    public static org.midonet.midolman.layer3.Route toRouteConfig(
            Route route) {

        int gateway = route.getNextHopGateway() == null ?
                org.midonet.midolman.layer3.Route.NO_GATEWAY :
                IPv4Addr.stringToInt(route.getNextHopGateway());
        org.midonet.midolman.layer3.Route routeConfig =
                new org.midonet.midolman.layer3.Route(
                        IPv4Addr.stringToInt(route.getSrcNetworkAddr()),
                        route.getSrcNetworkLength(),
                        IPv4Addr.stringToInt(route.getDstNetworkAddr()),
                        route.getDstNetworkLength(),
                        route.getNextHop(),
                        route.getNextHopPort(),
                        gateway,
                        route.getWeight(),
                        route.getAttributes(),
                        route.getRouterId()
                );

        return routeConfig;
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
                .setRouterId(route.routerId);
    }

    public static org.midonet.midolman.rules.Rule toRuleConfig(Rule rule) {

        org.midonet.midolman.rules.Rule ruleConfig = null;
        if (rule instanceof LiteralRule) {
            LiteralRule typedRule = (LiteralRule) rule;

            ruleConfig = new org.midonet.midolman.rules.LiteralRule(
                    typedRule.getCondition(),
                    typedRule.getAction()
            );
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

        if (ruleConfig == null)
            return ruleConfig;

        ruleConfig.chainId = rule.getChainId();
        ruleConfig.setProperties(rule.getProperties());

        return ruleConfig;
    }

    public static Rule fromRuleConfig(
            org.midonet.midolman.rules.Rule ruleConfig) {

        Rule rule = null;

        if (ruleConfig instanceof org.midonet.midolman.rules.LiteralRule) {
            rule = new LiteralRule(ruleConfig.getCondition(),
                    ruleConfig.action);
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
                .setProperties(ruleConfig.getProperties());
    }

    public static BridgeDhcpZkManager.Host toDhcpHostConfig(
            org.midonet.cluster.data.dhcp.Host host) {

        return new BridgeDhcpZkManager.Host(host.getMAC(), host.getIp(),
                host.getName());

    }

    public static org.midonet.cluster.data.dhcp.Host fromDhcpHostConfig
            (BridgeDhcpZkManager.Host hostConfig) {
        return new org.midonet.cluster.data.dhcp.Host()
                .setIp(hostConfig.getIp())
                .setMAC(hostConfig.getMac())
                .setName(hostConfig.getName());
    }

    public static BridgeDhcpZkManager.Subnet toDhcpSubnetConfig(
            Subnet subnet) {

        List<BridgeDhcpZkManager.Opt121> opt121Configs =
                new ArrayList<BridgeDhcpZkManager.Opt121>();
        for (Opt121 opt121 : subnet.getOpt121Routes()) {
            opt121Configs.add(toDhcpOpt121Config(opt121));
        }

        return new BridgeDhcpZkManager.Subnet(subnet.getSubnetAddr(),
                subnet.getDefaultGateway(), subnet.getServerAddr(),
                subnet.getDnsServerAddrs(), subnet.getInterfaceMTU(),
                opt121Configs);
    }

    public static Subnet fromDhcpSubnetConfig(
            BridgeDhcpZkManager.Subnet subnetConfig) {

        List<Opt121> opt121s = new ArrayList<Opt121>();
        for (BridgeDhcpZkManager.Opt121 opt121Config
                : subnetConfig.getOpt121Routes()) {
            opt121s.add(fromDhcpOpt121Config(opt121Config));
        }

        return new Subnet()
                .setDefaultGateway(
                    IntIPv4.toIPv4Subnet(subnetConfig.getDefaultGateway()))
                .setOpt121Routes(opt121s)
                .setServerAddr(subnetConfig.getServerAddr())
                .setDnsServerAddrs(subnetConfig.getDnsServerAddrs())
                .setInterfaceMTU(subnetConfig.getInterfaceMTU())
                .setSubnetAddr(
                    IntIPv4.toIPv4Subnet(subnetConfig.getSubnetAddr()));
    }

    public static BridgeDhcpZkManager.Opt121 toDhcpOpt121Config(
            Opt121 opt121) {
        return new BridgeDhcpZkManager.Opt121(opt121.getRtDstSubnet(),
                opt121.getGateway());
    }

    public static Opt121 fromDhcpOpt121Config(BridgeDhcpZkManager.Opt121
                                              opt121Config) {
        return new Opt121()
                .setGateway(IntIPv4.toIPv4Subnet(opt121Config.getGateway()))
                .setRtDstSubnet(IntIPv4.toIPv4Subnet(opt121Config.getRtDstSubnet()));
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

    public static HostDirectory.ErrorLogItem toHostErrorLogItemConfig(
            ErrorLogItem errorLogItem) {
        HostDirectory.ErrorLogItem errorLogItemConfig = new HostDirectory
                .ErrorLogItem();
        errorLogItemConfig.setCommandId(errorLogItem.getCommandId());
        errorLogItemConfig.setError(errorLogItem.getError());
        errorLogItemConfig.setInterfaceName(errorLogItem.getInterfaceName());
        errorLogItemConfig.setTime(errorLogItem.getTime());
        return errorLogItemConfig;
    }

    public static ErrorLogItem fromHostErrorLogItemConfig(
            HostDirectory.ErrorLogItem errorLogItemConfig) {
        return new ErrorLogItem()
                .setCommandId(errorLogItemConfig.getCommandId())
                .setError(errorLogItemConfig.getError())
                .setInterfaceName(errorLogItemConfig.getInterfaceName())
                .setTime(errorLogItemConfig.getTime());
    }

    public static HostDirectory.Command toHostCommandConfig(Command command) {
        HostDirectory.Command commandConfig = new HostDirectory.Command();
        commandConfig.setInterfaceName(command.getInterfaceName());
        commandConfig.setCommandList(command.getCommandList());
        return commandConfig;
    }

    public static Command fromHostCommandConfig(
            HostDirectory.Command commandConfig) {
        return new Command()
                .setInterfaceName(commandConfig.getInterfaceName())
                .setCommandList(commandConfig.getCommandList());
    }

    public static HostDirectory.Interface toHostInterfaceConfig(
            Interface intf) {
        HostDirectory.Interface interfaceConfig =
                new HostDirectory.Interface();
        interfaceConfig.setPortType(intf.getPortType());
        interfaceConfig.setAddresses(intf.getAddresses());
        interfaceConfig.setEndpoint(intf.getEndpoint());
        interfaceConfig.setMac(intf.getMac());
        interfaceConfig.setMtu(intf.getMtu());
        interfaceConfig.setPortType(intf.getPortType());
        interfaceConfig.setName(intf.getName());
        interfaceConfig.setStatus(intf.getStatus());
        interfaceConfig.setType(intf.getType());
        return interfaceConfig;
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
        metadata.setTunnelZones(new HashSet<UUID>(
                host.getTunnelZones()));

        return metadata;
    }

    public static HostDirectory.VirtualPortMapping toHostVirtPortMappingConfig(
            VirtualPortMapping mapping) {

        HostDirectory.VirtualPortMapping mappingConfig =
                new HostDirectory.VirtualPortMapping();
        mappingConfig.setLocalDeviceName(mapping.getLocalDeviceName());
        mappingConfig.setVirtualPortId(mapping.getVirtualPortId());
        return mappingConfig;
    }

    public static VirtualPortMapping fromHostVirtPortMappingConfig(
            HostDirectory.VirtualPortMapping mappingConfig) {

        return new VirtualPortMapping()
                .setLocalDeviceName(mappingConfig.getLocalDeviceName())
                .setVirtualPortId(mappingConfig.getVirtualPortId());
    }

    public static TaggableConfig
    toTaggableConfig(TaggableEntity taggableData) {
        // These conditionals on implementing classes are ugly, but such
        // conditionals are everywhere in this class:P
        TaggableConfig config = null;
        if (taggableData instanceof Bridge) {
            config = toBridgeConfig((Bridge) taggableData);

        } else {
            throw new RuntimeException(
                    "No conversion to TaggableConfig exists for "
                            + taggableData.getClass());
        }
        return config;
    }

}
