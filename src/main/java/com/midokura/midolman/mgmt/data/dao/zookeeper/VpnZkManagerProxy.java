/*
 * @(#)VpnZkManagerProxy        1.6 11/10/25
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dao.VpnDao;
import com.midokura.midolman.mgmt.data.dto.Vpn;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.VpnZkManager;
import com.midokura.midolman.state.VpnZkManager.VpnConfig;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Data access class for VPN.
 *
 * @version 1.6 25 Oct 2011
 * @author Yoshi Tamura
 */
public class VpnZkManagerProxy extends ZkMgmtManager implements VpnDao,
        OwnerQueryable {

    private VpnZkManager zkManager = null;

    /**
     * Constructor
     *
     * @param zkConn
     *            Zookeeper connection string
     */
    public VpnZkManagerProxy(Directory zk, String basePath, String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
        zkManager = new VpnZkManager(zk, basePath);
    }

    /**
     * Add a JAXB object the ZK directories.
     *
     * @param vpn
     *            Vpn object to add.
     * @throws ZkStateSerializationException
     * @throws StateAccessException
     * @throws UnknownHostException
     * @throws Exception
     *             Error connecting to Zookeeper.
     */
    @Override
    public UUID create(Vpn vpn) throws StateAccessException {
        return zkManager.create(vpn.toConfig());
    }

    /**
     * Fetch a JAXB object from the ZooKeeper.
     *
     * @param id
     *            Vpn UUID to fetch.
     * @throws StateAccessException
     * @throws Exception
     *             Error connecting to Zookeeper.
     */
    @Override
    public Vpn get(UUID id) throws StateAccessException {
        // TODO: Throw NotFound exception here.
        return new Vpn(id, zkManager.get(id).value);
    }

    @Override
    public List<Vpn> list(UUID portId) throws StateAccessException {
        List<Vpn> vpns = new ArrayList<Vpn>();
        List<ZkNodeEntry<UUID, VpnConfig>> entries = zkManager.list(portId);
        for (ZkNodeEntry<UUID, VpnConfig> entry : entries) {
            vpns.add(new Vpn(entry.key, entry.value));
        }
        return vpns;
    }

    @Override
    public void delete(UUID id) throws StateAccessException {
        // TODO: catch NoNodeException if does not exist.
        zkManager.delete(id);
    }

    @Override
    public String getOwner(UUID id) throws StateAccessException {
        Vpn vpn = get(id);
        OwnerQueryable manager = new PortZkManagerProxy(zk,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());
        return manager.getOwner(vpn.getPublicPortId());
    }
}
