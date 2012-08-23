/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data;

import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalBridgePortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedBridgePortConfig;
import com.midokura.midonet.cluster.data.ports.LogicalBridgePort;
import com.midokura.midonet.cluster.data.ports.LogicalRouterPort;
import com.midokura.midonet.cluster.data.ports.MaterializedBridgePort;
import com.midokura.midonet.cluster.data.ports.MaterializedRouterPort;
import static com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import static com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;

/**
 * Factory class for creating / converting {@link Port} instances
 */
public class Ports {

    public static MaterializedBridgePort materializedBridgePort(Bridge bridge) {
        return
            new MaterializedBridgePort()
                .setDeviceId(bridge.getId());
    }

    public static PortConfig toPortConfig(Port port) {

        PortConfig portConfig = null;
        if (port instanceof MaterializedBridgePort) {
            MaterializedBridgePort typedPort =
                (MaterializedBridgePort) port;

            MaterializedBridgePortConfig typedPortConfig =
                new MaterializedBridgePortConfig();

            typedPortConfig.setHostId(typedPort.getHostId());
            typedPortConfig.setInterfaceName(typedPort.getInterfaceName());

            portConfig = typedPortConfig;
        }

        if (port instanceof LogicalBridgePort) {
            LogicalBridgePort typedPort = (LogicalBridgePort) port;

            LogicalBridgePortConfig typedPortConfig =
                new LogicalBridgePortConfig();

            typedPortConfig.setPeerId(typedPort.getPeerId());

            portConfig = typedPortConfig;
        }

        if (port instanceof MaterializedRouterPort) {
            MaterializedRouterPort typedPort = (MaterializedRouterPort) port;

            MaterializedRouterPortConfig typedPortConfig =
                new MaterializedRouterPortConfig();

            portConfig = typedPortConfig;

            typedPortConfig.setHostId(typedPort.getHostId());
            typedPortConfig.setInterfaceName(typedPort.getInterfaceName());
            typedPortConfig.setBgps(typedPort.getBgps());

            typedPortConfig.setLocalNwAddr(typedPort.getLocalNwAddr());
            typedPortConfig.localNwLength = typedPort.getLocalNwLength();

            typedPortConfig.setHwAddr(typedPort.getHwAddr());
            typedPortConfig.setPortAddr(typedPort.getPortAddr());
            typedPortConfig.setNwAddr(typedPort.getNwAddr());
            typedPortConfig.nwLength = typedPort.getNwLength();
        }

        if (port instanceof LogicalRouterPort) {
            LogicalRouterPort typedPort = (LogicalRouterPort) port;

            LogicalRouterPortConfig typedPortConfig =
                new LogicalRouterPortConfig();

            portConfig = typedPortConfig;

            typedPortConfig.setPeerId(typedPort.getPeerId());

            typedPortConfig.setHwAddr(typedPort.getHwAddr());
            typedPortConfig.setPortAddr(typedPort.getPortAddr());
            typedPortConfig.setNwAddr(typedPort.getNwAddr());
            typedPortConfig.nwLength = typedPort.getNwLength();
        }

        if (portConfig == null)
            return portConfig;

        portConfig.device_id = port.getDeviceId();
        portConfig.inboundFilter = port.getInboundFilter();
        portConfig.outboundFilter = port.getOutboundFilter();
        portConfig.greKey = port.getGreKey();
        portConfig.properties = port.getProperties();
        portConfig.portGroupIDs = port.getPortGroups();

        return portConfig;  //To change body of created methods use File | Settings | File Templates.
    }

    public static Port fromPortConfig(PortConfig portConfig) {

        Port port = null;

        if (portConfig instanceof LogicalBridgePortConfig) {
            LogicalBridgePortConfig bridgePortConfig = (LogicalBridgePortConfig) portConfig;
            LogicalBridgePort bridgePort = new LogicalBridgePort();

            bridgePort.setPeerId(bridgePortConfig.peerId());
            port = bridgePort;
        }

        if (portConfig instanceof MaterializedBridgePortConfig) {
            MaterializedBridgePortConfig bridgePortConfig = (MaterializedBridgePortConfig) portConfig;
            MaterializedBridgePort bridgePort = new MaterializedBridgePort();

            bridgePort.setHostId(bridgePortConfig.getHostId());
            bridgePort.setInterfaceName(bridgePortConfig.getInterfaceName());
            port = bridgePort;
        }

        if (portConfig instanceof LogicalRouterPortConfig) {
            LogicalRouterPortConfig routerPortConfig = (LogicalRouterPortConfig) portConfig;

            port = new LogicalRouterPort()
                .setPeerId(routerPortConfig.peerId())
                .setNwAddr(routerPortConfig.getNwAddr())
                .setNwLength(routerPortConfig.nwLength)
                .setPortAddr(routerPortConfig.getPortAddr())
                .setHwAddr(routerPortConfig.getHwAddr());
        }

        if (portConfig instanceof MaterializedRouterPortConfig) {
            MaterializedRouterPortConfig routerPortConfig =
                (MaterializedRouterPortConfig) portConfig;

            port = new MaterializedRouterPort()
                .setLocalNwAddr(routerPortConfig.getLocalNwAddr())
                .setLocalNwLength(routerPortConfig.localNwLength)
                .setHostId(routerPortConfig.getHostId())
                .setInterfaceName(routerPortConfig.getInterfaceName())
                .setNwAddr(routerPortConfig.getNwAddr())
                .setNwLength(routerPortConfig.nwLength)
                .setPortAddr(routerPortConfig.getPortAddr())
                .setHwAddr(routerPortConfig.getHwAddr());
        }

        if (port == null)
            return null;

        return port
            .setDeviceId(portConfig.device_id)
            .setGreKey(portConfig.greKey)
            .setInboundFilter(portConfig.inboundFilter)
            .setOutboundFilter(portConfig.outboundFilter)
            .setProperties(portConfig.properties)
            .setPortGroups(portConfig.portGroupIDs);
    }
}
