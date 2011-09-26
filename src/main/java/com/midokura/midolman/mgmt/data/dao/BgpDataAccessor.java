/*
 * @(#)BgpDataAccessor        1.6 11/09/11
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.ZooKeeper;

import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.state.BgpZkManager;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.BgpZkManager.BgpConfig;

/**
 * Data access class for BGP.
 * 
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
public class BgpDataAccessor extends DataAccessor {

    /**
     * Constructor
     * 
     * @param zkConn
     *            Zookeeper connection string
     */
    public BgpDataAccessor(ZooKeeper zkConn, String rootPath,
            String mgmtRootPath) {
        super(zkConn, rootPath, mgmtRootPath);
    }

    private BgpZkManager getBgpZkManager() throws Exception {
        return new BgpZkManager(zkConn, zkRoot);
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
        BgpZkManager manager = getBgpZkManager();
        return manager.create(bgp.toConfig());
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
        BgpZkManager manager = getBgpZkManager();
        // TODO: Throw NotFound exception here.
        return Bgp.createBgp(id, manager.get(id).value);
    }

    public Bgp[] list(UUID portId) throws Exception {
        BgpZkManager manager = getBgpZkManager();
        List<Bgp> bgps = new ArrayList<Bgp>();
        List<ZkNodeEntry<UUID, BgpConfig>> entries = manager.list(portId);
        for (ZkNodeEntry<UUID, BgpConfig> entry : entries) {
            bgps.add(Bgp.createBgp(entry.key, entry.value));
        }
        return bgps.toArray(new Bgp[bgps.size()]);
    }

    public void update(UUID id, Bgp bgp) throws Exception {
        // BgpZkManager manager = getBgpZkManager();
        // ZkNodeEntry<UUID, BgpConfig> entry = manager.get(id);
        // copyBgp(bgp, entry.value);
        // manager.update(entry);
    }

    public void delete(UUID id) throws Exception {
        // TODO: catch NoNodeException if does not exist.
        getBgpZkManager().delete(id);
    }
}
