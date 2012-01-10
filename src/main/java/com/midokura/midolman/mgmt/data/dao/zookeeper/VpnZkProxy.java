/*
 * @(#)VpnZkProxy        1.6 11/10/25
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dao.VpnDao;
import com.midokura.midolman.mgmt.data.dto.Vpn;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.VpnZkManager;
import com.midokura.midolman.state.VpnZkManager.VpnConfig;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Data access class for VPN.
 *
 * @version 1.6 25 Oct 2011
 * @author Yoshi Tamura
 */
public class VpnZkProxy implements VpnDao {

    private VpnZkManager dataAccessor = null;

    /**
     * Constructor.
     *
     * @param dataAccessor
     *            VPN data accessor.
     */
    public VpnZkProxy(VpnZkManager dataAccessor) {
        this.dataAccessor = dataAccessor;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.VpnDao#create(com.midokura.midolman
     * .mgmt.data.dto.Vpn)
     */
    @Override
    public UUID create(Vpn vpn) throws StateAccessException {
        return dataAccessor.create(vpn.toConfig());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.VpnDao#delete(java.util.UUID)
     */
    @Override
    public void delete(UUID id) throws StateAccessException {
        // TODO: catch NoNodeException if does not exist.
        dataAccessor.delete(id);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.VpnDao#get(java.util.UUID)
     */
    @Override
    public Vpn get(UUID id) throws StateAccessException {
        // TODO: Throw NotFound exception here.
        return new Vpn(id, dataAccessor.get(id).value);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.VpnDao#list(java.util.UUID)
     */
    @Override
    public List<Vpn> list(UUID portId) throws StateAccessException {
        List<Vpn> vpns = new ArrayList<Vpn>();
        List<ZkNodeEntry<UUID, VpnConfig>> entries = dataAccessor.list(portId);
        for (ZkNodeEntry<UUID, VpnConfig> entry : entries) {
            vpns.add(new Vpn(entry.key, entry.value));
        }
        return vpns;
    }
}
