/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */

package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;

import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.ZkStateSerializationException;
import org.midonet.packets.IntIPv4;

public class VpnZkManager extends ZkManager {

    public static enum VpnType {
        OPENVPN_SERVER, OPENVPN_CLIENT, OPENVPN_TCP_SERVER, OPENVPN_TCP_CLIENT,
    }

    public static final class VpnConfig {
        public UUID publicPortId;
        public UUID privatePortId;
        public String remoteIp;
        public VpnType vpnType;
        public int port;

        public VpnConfig(UUID publicPortId, UUID privatePortId,
                String remoteIp, VpnType vpnType, int port) {
            this.publicPortId = publicPortId;
            this.privatePortId = privatePortId;
            // check if it's a valid IP
            if (remoteIp != null)
                IntIPv4.fromString(remoteIp);
            else {
                if (vpnType == VpnType.OPENVPN_CLIENT
                        || vpnType == VpnType.OPENVPN_TCP_CLIENT)
                    throw new IllegalArgumentException(
                            "Vpn client: remote address is null!");
            }
            this.remoteIp = remoteIp;
            this.vpnType = vpnType;
            this.port = port;
        }

        // Default constructor for the Jackson deserialization.
        public VpnConfig() {
            super();
        }
    }

    /**
     * VpnZkManager constructor.
     *
     * @param zk
     *            Zookeeper object.
     * @param basePath
     *            Directory to set as the base.
     */
    public VpnZkManager(Directory zk, String basePath) {
        super(zk, basePath);
    }

    public List<Op> prepareVpnCreate(UUID id, VpnConfig config)
            throws ZkStateSerializationException {

        List<Op> ops = new ArrayList<Op>();

        ops.add(Op.create(paths.getVpnPath(id),
                serializer.serialize(config), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        ops.add(Op.create(paths.getPortVpnPath(config.publicPortId, id),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(paths.getPortVpnPath(config.privatePortId, id),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        return ops;
    }

    public List<Op> prepareVpnDelete(UUID id) throws StateAccessException {
        return prepareVpnDelete(id, get(id));
    }

    public List<Op> prepareVpnDelete(UUID id, VpnConfig config)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        // Delete the port vpn entry
        ops.add(Op.delete(paths.getPortVpnPath(config.publicPortId, id),
                -1));
        ops.add(Op.delete(paths.getPortVpnPath(config.privatePortId, id),
                -1));

        // Delete the vpn
        ops.add(Op.delete(paths.getVpnPath(id), -1));

        // Unlock if exists
        if (this.exists(paths.getAgentVpnPath(id))
                && this.exists(paths
                        .getAgentPortPath(config.privatePortId))) {
            ops.add(Op.delete(paths.getAgentVpnPath(id), -1));
            ops.add(Op.delete(
                    paths.getAgentPortPath(config.privatePortId), -1));
        }

        return ops;
    }

    public List<Op> preparePortDelete(UUID portId) throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        List<UUID> vpnList = list(portId);
        for (UUID vpn : vpnList) {
            ops.addAll(prepareVpnDelete(vpn));
        }

        return ops;
    }

    public UUID create(VpnConfig vpn) throws StateAccessException {
        UUID id = UUID.randomUUID();
        multi(prepareVpnCreate(id, vpn));
        return id;
    }

    public VpnConfig get(UUID id, Runnable watcher) throws StateAccessException {
        byte[] data = get(paths.getVpnPath(id), watcher);
        return serializer.deserialize(data, VpnConfig.class);
    }

    public VpnConfig get(UUID id) throws StateAccessException {
        return get(id, null);
    }

    public boolean exists(UUID id) throws StateAccessException {
        return exists(paths.getVpnPath(id));
    }

    // List all vpns.
    public List<UUID> listAll(Runnable watcher) throws StateAccessException {
        List<UUID> result = new ArrayList<UUID>();
        Set<String> vpnIds = getChildren(paths.getVpnPath(), watcher);
        for (String vpnId : vpnIds) {
            // For now, get each one.
            result.add(UUID.fromString(vpnId));
        }
        return result;
    }

    public List<UUID> listAll() throws StateAccessException {
        return listAll(null);
    }

    public List<UUID> list(UUID portId, Runnable watcher)
            throws StateAccessException {
        List<UUID> result = new ArrayList<UUID>();
        Set<String> vpnIds = getChildren(paths.getPortVpnPath(portId),
                watcher);
        for (String vpnId : vpnIds) {
            // For now, get each one.
            result.add(UUID.fromString(vpnId));
        }
        return result;
    }

    public List<UUID> list(UUID portId) throws StateAccessException {
        return list(portId, null);
    }

    public void update(UUID id, VpnConfig config) throws StateAccessException {
        byte[] data = serializer.serialize(config);
        update(paths.getVpnPath(id), data);
    }

    public void delete(UUID id) throws StateAccessException {
        multi(prepareVpnDelete(id));
    }

    public List<Op> prepareVpnLock(UUID id, Long sessionId)
            throws StateAccessException {
        VpnConfig vpnNode = get(id);

        List<Op> ops = new ArrayList<Op>();
        byte[] data = serializer.serialize(sessionId);

        // Add UUID of vpn, private port as ephemeral nodes.
        ops.add(Op.create(paths.getAgentVpnPath(id), data,
                Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL));
        ops.add(Op.create(paths.getAgentPortPath(vpnNode.privatePortId),
                data, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL));

        return ops;
    }

    public void lock(UUID id, Long sessionId) throws StateAccessException {
        multi(prepareVpnLock(id, sessionId));
    }

    public List<Op> prepareVpnUnlock(UUID id) throws StateAccessException {
        VpnConfig vpnNode = get(id);

        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(paths.getAgentVpnPath(id), -1));
        ops.add(Op.delete(paths.getAgentPortPath(vpnNode.privatePortId),
                -1));

        return ops;
    }

    public void unlock(UUID id) throws StateAccessException {
        multi(prepareVpnUnlock(id));
    }

    public void wait(UUID id, Runnable watcher) throws StateAccessException {
        get(paths.getAgentVpnPath(id), watcher);
    }
}
