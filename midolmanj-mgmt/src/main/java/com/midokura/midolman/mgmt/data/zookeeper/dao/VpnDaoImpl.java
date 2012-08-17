/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */

package com.midokura.midolman.mgmt.data.zookeeper.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.inject.Inject;
import com.midokura.midolman.mgmt.data.dao.VpnDao;
import com.midokura.midolman.mgmt.data.dto.Vpn;
import com.midokura.midolman.state.InvalidStateOperationException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.zkManagers.VpnZkManager;

/**
 * Data access class for VPN.
 */
public class VpnDaoImpl implements VpnDao {

    private VpnZkManager dataAccessor = null;

    /**
     * Constructor.
     *
     * @param dataAccessor
     *            VPN data accessor.
     */
    @Inject
    public VpnDaoImpl(VpnZkManager dataAccessor) {
        this.dataAccessor = dataAccessor;
    }

    @Override
    public UUID create(Vpn vpn) throws StateAccessException {
        return dataAccessor.create(vpn.toConfig());
    }

    @Override
    public void delete(UUID id) throws StateAccessException {
        // TODO: catch NoNodeException if does not exist.
        dataAccessor.delete(id);
    }

    @Override
    public Vpn get(UUID id) throws StateAccessException {
        try {
            return new Vpn(id, dataAccessor.get(id));
        } catch (NoStatePathException e) {
            return null;
        }
    }

    @Override
    public void update(Vpn obj)
            throws StateAccessException, InvalidStateOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Vpn> findByPort(UUID portId) throws StateAccessException {
        List<Vpn> vpns = new ArrayList<Vpn>();
        List<UUID> ids = dataAccessor.list(portId);
        for (UUID id : ids) {
            vpns.add(new Vpn(id, dataAccessor.get(id)));
        }
        return vpns;
    }
}
