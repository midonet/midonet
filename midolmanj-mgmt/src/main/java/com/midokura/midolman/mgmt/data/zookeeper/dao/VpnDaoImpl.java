/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */

package com.midokura.midolman.mgmt.data.zookeeper.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dao.VpnDao;
import com.midokura.midolman.mgmt.data.dto.Vpn;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.VpnZkManager;

/**
 * Data access class for VPN.
 *
 * @version 1.6 25 Oct 2011
 * @author Yoshi Tamura
 */
public class VpnDaoImpl implements VpnDao {

    private VpnZkManager dataAccessor = null;

    /**
     * Constructor.
     *
     * @param dataAccessor
     *            VPN data accessor.
     */
    public VpnDaoImpl(VpnZkManager dataAccessor) {
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
        try {
            return new Vpn(id, dataAccessor.get(id));
        } catch (NoStatePathException e) {
            return null;
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.VpnDao#list(java.util.UUID)
     */
    @Override
    public List<Vpn> list(UUID portId) throws StateAccessException {
        List<Vpn> vpns = new ArrayList<Vpn>();
        List<UUID> ids = dataAccessor.list(portId);
        for (UUID id : ids) {
            vpns.add(new Vpn(id, dataAccessor.get(id)));
        }
        return vpns;
    }
}
