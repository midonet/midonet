/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster;

import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.inject.Inject;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.state.PathBuilder;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkConfigSerializer;
import com.midokura.midolman.state.zkManagers.AvailabilityZoneZkManager;
import com.midokura.midolman.state.zkManagers.BridgeZkManager;
import com.midokura.midolman.state.zkManagers.PortZkManager;
import com.midokura.midonet.cluster.data.AvailabilityZone;
import com.midokura.midonet.cluster.data.Bridge;
import com.midokura.midonet.cluster.data.BridgeName;
import com.midokura.midonet.cluster.data.Bridges;
import com.midokura.midonet.cluster.data.Host;
import com.midokura.midonet.cluster.data.Hosts;
import com.midokura.midonet.cluster.data.Port;
import com.midokura.midonet.cluster.data.Ports;


public class LocalDataClientImpl implements DataClient {

    @Inject
    private BridgeZkManager bridgeZkManager;

    @Inject
    private PortZkManager portZkManager;

    @Inject
    private HostZkManager hostZkManager;

    @Inject
    private AvailabilityZoneZkManager zonesZkManager;

    @Inject
    private PathBuilder pathBuilder;

    @Inject
    private ZkConfigSerializer serializer;

    private final static Logger log =
        LoggerFactory.getLogger(LocalDataClientImpl.class);

    @Override
    public Bridge bridgesGetByName(String tenantId, String name)
        throws StateAccessException {
        log.debug("Entered: tenantId={}, name={}", tenantId, name);

        Bridge bridge = null;
        String path = pathBuilder.getTenantBridgeNamePath(tenantId, name)
                                 .toString();

        if (bridgeZkManager.exists(path)) {
            byte[] data = bridgeZkManager.get(path);
            BridgeName.Data bridgeNameData =
                serializer.deserialize(data, BridgeName.Data.class);
            bridge = bridgesGet(bridgeNameData.id);
        }

        log.debug("Exiting: bridge={}", bridge);
        return bridge;
    }

    @Override
    public Bridge bridgesCreate(@Nonnull Bridge bridge)
        throws StateAccessException {
        log.debug("BridgeZkDaoImpl.create entered: bridge={}", bridge);

        if (bridge.getId() == null) {
            bridge.setId(UUID.randomUUID());
        }

        BridgeZkManager.BridgeConfig bridgeConfig =
            Bridges.toBridgeZkConfig(bridge);

        List<Op> ops =
            bridgeZkManager.prepareBridgeCreate(bridge.getId(), bridgeConfig);

//        BridgeName bridgeName = new BridgeName(bridge);
//
//        byte[] data = serializer.serialize(bridgeName);
//        ops.add(
//            bridgeZkManager.getPersistentCreateOp(
//                pathBuilder.getTenantBridgeNamePath(
//                    bridge.getProperty(Bridge.Property.tenant_id),
//                    bridgeConfig.name),
//                data));

        bridgeZkManager.multi(ops);

        log.debug("BridgeZkDaoImpl.create exiting: bridge={}", bridge);
        return bridge;
    }


    @Override
    public Bridge bridgesGet(UUID id) throws StateAccessException {
        log.debug("Entered: id={}", id);

        Bridge bridge = null;
        if (bridgeZkManager.exists(id)) {
            bridge = Bridges.fromZkBridgeConfig(bridgeZkManager.get(id));
        }

        log.debug("Exiting: bridge={}", bridge);
        return bridge;
    }

    @Override
    public void bridgesDelete(UUID id) throws StateAccessException {
        bridgeZkManager.delete(id);
    }

    @Override
    public boolean portsExists(UUID id) throws StateAccessException {
        return portZkManager.exists(id);
    }

    @Override
    public UUID portsCreate(Port port) throws StateAccessException {
        return portZkManager.create(Ports.toPortConfig(port));
    }

    @Override
    public Port portsGet(UUID id) throws StateAccessException {
        return Ports.fromPortConfig(portZkManager.get(id));
    }

    @Override
    public UUID availabilityZonesCreate(AvailabilityZone<?, ?> zone)
        throws StateAccessException {
        return zonesZkManager.createZone(zone, null);
    }

    @Override
    public UUID availabilityZonesAddMembership(UUID zoneId, AvailabilityZone.HostConfig<?, ?> hostConfig)
        throws StateAccessException {
        return zonesZkManager.addMembership(zoneId, hostConfig);
    }

    @Override
    public UUID hostsCreate(UUID hostId, Host host) throws StateAccessException {
        hostZkManager.createHost(hostId, Hosts.toHostConfig(host));
        return hostId;
    }

    @Override
    public void hostsAddVrnPortMapping(UUID hostId, UUID portId,
                                       String localPortName)
            throws StateAccessException {
        hostZkManager.addVirtualPortMapping(hostId,
                new HostDirectory.VirtualPortMapping(portId, localPortName));
    }

    @Override
    public void hostsAddDatapathMapping(UUID hostId, String datapathName)
            throws StateAccessException {
        hostZkManager.addVirtualDatapathMapping(hostId, datapathName);
    }

    @Override
    public void hostsRemoveVrnPortMapping(UUID hostId, UUID portId)
            throws StateAccessException {
        hostZkManager.removeVirtualPortMapping(hostId, portId);
    }

    @Override
    public void portsSetActive(UUID portID, boolean active) {
        //XXX: pino
    }
}
