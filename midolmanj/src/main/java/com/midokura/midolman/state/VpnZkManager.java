/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */

package com.midokura.midolman.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.packets.IntIPv4;

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

    public List<Op> prepareVpnCreate(ZkNodeEntry<UUID, VpnConfig> vpnNode)
            throws ZkStateSerializationException {

        List<Op> ops = new ArrayList<Op>();

        ops.add(Op.create(pathManager.getVpnPath(vpnNode.key),
                serializer.serialize(vpnNode.value), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getPortVpnPath(
                vpnNode.value.publicPortId, vpnNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getPortVpnPath(
                vpnNode.value.privatePortId, vpnNode.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        return ops;
    }

    public List<Op> prepareVpnDelete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        return prepareVpnDelete(get(id));
    }

    public List<Op> prepareVpnDelete(ZkNodeEntry<UUID, VpnConfig> entry)
            throws StateAccessException, ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();

        // Delete the port vpn entry
        ops.add(Op.delete(
                pathManager.getPortVpnPath(entry.value.publicPortId, entry.key),
                -1));
        ops.add(Op.delete(pathManager.getPortVpnPath(entry.value.privatePortId,
                entry.key), -1));

        // Delete the vpn
        ops.add(Op.delete(pathManager.getVpnPath(entry.key), -1));

        // Unlock if exists
        if (this.exists(pathManager.getAgentVpnPath(entry.key))
                && this.exists(pathManager
                        .getAgentPortPath(entry.value.privatePortId))) {
            ops.add(Op.delete(pathManager.getAgentVpnPath(entry.key), -1));
            ops.add(Op.delete(
                    pathManager.getAgentPortPath(entry.value.privatePortId), -1));
        }

        return ops;
    }

    public List<Op> preparePortDelete(UUID portId) throws StateAccessException,
            ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();

        List<ZkNodeEntry<UUID, VpnConfig>> vpnList = list(portId);
        for (ZkNodeEntry<UUID, VpnConfig> vpn : vpnList) {
            ops.addAll(prepareVpnDelete(vpn));
        }

        return ops;
    }

    public UUID create(VpnConfig vpn) throws StateAccessException,
            ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, VpnConfig> vpnNode = new ZkNodeEntry<UUID, VpnConfig>(
                id, vpn);
        multi(prepareVpnCreate(vpnNode));
        return id;
    }

    public ZkNodeEntry<UUID, VpnConfig> get(UUID id, Runnable watcher)
            throws StateAccessException, ZkStateSerializationException {
        byte[] data = get(pathManager.getVpnPath(id), watcher);
        VpnConfig config = serializer.deserialize(data, VpnConfig.class);
        return new ZkNodeEntry<UUID, VpnConfig>(id, config);
    }

    public ZkNodeEntry<UUID, VpnConfig> get(UUID id)
            throws StateAccessException, ZkStateSerializationException {
        return get(id, null);
    }

    // List all vpns.
    public List<ZkNodeEntry<UUID, VpnConfig>> listAll(Runnable watcher)
            throws StateAccessException, ZkStateSerializationException {
        List<ZkNodeEntry<UUID, VpnConfig>> result = new ArrayList<ZkNodeEntry<UUID, VpnConfig>>();
        Set<String> vpnIds = getChildren(pathManager.getVpnPath(), watcher);
        for (String vpnId : vpnIds) {
            // For now, get each one.
            result.add(get(UUID.fromString(vpnId)));
        }
        return result;
    }

    public List<ZkNodeEntry<UUID, VpnConfig>> listAll()
            throws StateAccessException, ZkStateSerializationException {
        return listAll(null);
    }

    public List<ZkNodeEntry<UUID, VpnConfig>> list(UUID portId, Runnable watcher)
            throws StateAccessException, ZkStateSerializationException {
        List<ZkNodeEntry<UUID, VpnConfig>> result = new ArrayList<ZkNodeEntry<UUID, VpnConfig>>();
        Set<String> vpnIds = getChildren(pathManager.getPortVpnPath(portId),
                watcher);
        for (String vpnId : vpnIds) {
            // For now, get each one.
            result.add(get(UUID.fromString(vpnId)));
        }
        return result;
    }

    public List<ZkNodeEntry<UUID, VpnConfig>> list(UUID portId)
            throws StateAccessException, ZkStateSerializationException {
        return list(portId, null);
    }

    public void update(ZkNodeEntry<UUID, VpnConfig> entry)
            throws StateAccessException, ZkStateSerializationException {
        byte[] data = serializer.serialize(entry.value);
        update(pathManager.getVpnPath(entry.key), data);
    }

    public void delete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        multi(prepareVpnDelete(id));
    }

    public List<Op> prepareVpnLock(UUID id, Long sessionId)
            throws StateAccessException {
        ZkNodeEntry<UUID, VpnConfig> vpnNode = get(id);

        List<Op> ops = new ArrayList<Op>();
        byte[] data = serializer.serialize(sessionId);

        // Add UUID of vpn, private port as ephemeral nodes.
        ops.add(Op.create(pathManager.getAgentVpnPath(vpnNode.key), data,
                Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL));
        ops.add(Op.create(
                pathManager.getAgentPortPath(vpnNode.value.privatePortId),
                data, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL));

        return ops;
    }

    public void lock(UUID id, Long sessionId) throws StateAccessException {
        multi(prepareVpnLock(id, sessionId));
    }

    public List<Op> prepareVpnUnlock(UUID id) throws StateAccessException {
        ZkNodeEntry<UUID, VpnConfig> vpnNode = get(id);

        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getAgentVpnPath(vpnNode.key), -1));
        ops.add(Op.delete(
                pathManager.getAgentPortPath(vpnNode.value.privatePortId), -1));

        return ops;
    }

    public void unlock(UUID id) throws StateAccessException {
        multi(prepareVpnUnlock(id));
    }

    public void wait(UUID id, Runnable watcher) throws StateAccessException {
        ZkNodeEntry<UUID, VpnConfig> vpnNode = get(id);
        get(pathManager.getAgentVpnPath(vpnNode.key), watcher);
    }
}
