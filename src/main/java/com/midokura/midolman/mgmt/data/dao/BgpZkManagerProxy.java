/*
 * @(#)BgpZkManagerProxy        1.6 11/09/11
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.state.BgpZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.BgpZkManager.BgpConfig;

/**
 * Data access class for BGP.
 * 
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
public class BgpZkManagerProxy extends ZkMgmtManager {

    private BgpZkManager zkManager = null;

    /**
     * Constructor
     * 
     * @param zkConn
     *            Zookeeper connection string
     */
    public BgpZkManagerProxy(Directory zk, String basePath, String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
        zkManager = new BgpZkManager(zk, basePath);
    }

    /**
     * Add a JAXB object the ZK directories.
     * 
     * @param bgp
     *            Bgp object to add.
     * @throws Exception
     *             Error connecting to Zookeeper.
     */
    public UUID create(Bgp bgp) throws Exception {
        return zkManager.create(bgp.toConfig());
    }

    /**
     * Fetch a JAXB object from the ZooKeeper.
     * 
     * @param id
     *            Bgp UUID to fetch..
     * @throws Exception
     *             Error connecting to Zookeeper.
     */
    public Bgp get(UUID id) throws Exception {
        // TODO: Throw NotFound exception here.
        return Bgp.createBgp(id, zkManager.get(id).value);
    }

    public List<Bgp> list(UUID portId) throws Exception {
        List<Bgp> bgps = new ArrayList<Bgp>();
        List<ZkNodeEntry<UUID, BgpConfig>> entries = zkManager.list(portId);
        for (ZkNodeEntry<UUID, BgpConfig> entry : entries) {
            bgps.add(Bgp.createBgp(entry.key, entry.value));
        }
        return bgps;
    }

    public void update(UUID id, Bgp bgp) throws Exception {
        throw new UnsupportedOperationException(
                "BGP update is not currently supported.");
    }

    public void delete(UUID id) throws Exception {
        // TODO: catch NoNodeException if does not exist.
        zkManager.delete(id);
    }

    public UUID getTenant(UUID id) throws Exception {
        Bgp bgp = get(id);
        PortZkManagerProxy manager = new PortZkManagerProxy(zk, pathManager
                .getBasePath(), mgmtPathManager.getBasePath());
        return manager.getTenant(bgp.getPortId());
    }
}
