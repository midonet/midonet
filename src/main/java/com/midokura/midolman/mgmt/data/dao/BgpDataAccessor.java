/*
 * @(#)BgpDataAccessor        1.6 11/09/11
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.data.dao;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.state.BgpZkManager;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.BgpZkManager.BgpConfig;

/**
 * Data access class for bridge.
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
    public BgpDataAccessor(String zkConn) {
        super(zkConn);
    }

    private BgpZkManager getBgpZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn);
        return new BgpZkManager(conn.getZooKeeper(), "/midolman");
    }

    private static BgpConfig convertToConfig(Bgp bgp) throws Exception {
        return new BgpConfig(bgp.getPortId(), bgp.getLocalAS(),
                             InetAddress.getByName(bgp.getPeerAddr()),
                             bgp.getPeerAS());
    }

    private static Bgp convertToBgp(BgpConfig config) {
        Bgp b = new Bgp();
        b.setLocalAS(config.localAS);
        b.setPeerAddr(config.peerAddr.getHostAddress());
        b.setPeerAS(config.peerAS);
        b.setPortId(config.portId);
        return b;
    }

    private static Bgp convertToBgp(ZkNodeEntry<UUID, BgpConfig> entry) {
        Bgp b = convertToBgp(entry.value);
        b.setId(entry.key);
        return b;
    }
    
    private static void copyBgp(Bgp bgp, BgpConfig config) {
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
        return manager.create(convertToConfig(bgp));
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
        return convertToBgp(manager.get(id));
    }

    public Bgp[] list(UUID portId) throws Exception {
        BgpZkManager manager = getBgpZkManager();
        List<Bgp> bgps = new ArrayList<Bgp>();
        List<ZkNodeEntry<UUID, BgpConfig>> entries = manager.list(portId);
        for (ZkNodeEntry<UUID, BgpConfig> entry : entries) {
            bgps.add(convertToBgp(entry));
        }
        return bgps.toArray(new Bgp[bgps.size()]);
    }

    public void update(UUID id, Bgp bgp) throws Exception {
        //BgpZkManager manager = getBgpZkManager();
        //ZkNodeEntry<UUID, BgpConfig> entry = manager.get(id);
        //copyBgp(bgp, entry.value);
        //manager.update(entry);
    }
    
    public void delete(UUID id) throws Exception {
        // TODO: catch NoNodeException if does not exist.
        getBgpZkManager().delete(id);
    }
}
