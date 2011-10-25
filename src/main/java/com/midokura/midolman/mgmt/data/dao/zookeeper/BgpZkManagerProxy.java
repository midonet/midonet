/*
 * @(#)BgpZkManagerProxy        1.6 11/09/11
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.state.BgpZkManager;
import com.midokura.midolman.state.BgpZkManager.BgpConfig;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Data access class for BGP.
 * 
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
public class BgpZkManagerProxy extends ZkMgmtManager implements BgpDao,
        OwnerQueryable {

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
     * @throws ZkStateSerializationException
     * @throws StateAccessException
     * @throws UnknownHostException
     * @throws Exception
     *             Error connecting to Zookeeper.
     */
    @Override
    public UUID create(Bgp bgp) throws StateAccessException {
        return zkManager.create(bgp.toConfig());
    }

    /**
     * Fetch a JAXB object from the ZooKeeper.
     * 
     * @param id
     *            Bgp UUID to fetch..
     * @throws ZkStateSerializationException
     * @throws StateAccessException
     * @throws Exception
     *             Error connecting to Zookeeper.
     */
    @Override
    public Bgp get(UUID id) throws StateAccessException {
        // TODO: Throw NotFound exception here.
        return Bgp.createBgp(id, zkManager.get(id).value);
    }

    @Override
    public List<Bgp> list(UUID portId) throws StateAccessException {
        List<Bgp> bgps = new ArrayList<Bgp>();
        List<ZkNodeEntry<UUID, BgpConfig>> entries = zkManager.list(portId);
        for (ZkNodeEntry<UUID, BgpConfig> entry : entries) {
            bgps.add(Bgp.createBgp(entry.key, entry.value));
        }
        return bgps;
    }

    @Override
    public void delete(UUID id) throws StateAccessException {
        zkManager.delete(id);
    }

    @Override
    public String getOwner(UUID id) throws StateAccessException {
        Bgp bgp = get(id);
        OwnerQueryable manager = new PortZkManagerProxy(zk,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());
        return manager.getOwner(bgp.getPortId());
    }
}
