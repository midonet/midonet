/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.AdRouteDao;
import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.state.zkManagers.BgpZkManager;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access class for BGP.
 *
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
public class BgpDaoImpl implements BgpDao {

    private final static Logger log = LoggerFactory.getLogger(BgpDaoImpl.class);
    private final BgpZkManager dataAccessor;
    private final AdRouteDao adRouteDao;

    /**
     * Constructor.
     *
     * @param dataAccessor
     *            BGP data accessor.
     * @param adRouteDao
     *            AdRoute DAO
     */
    public BgpDaoImpl(BgpZkManager dataAccessor, AdRouteDao adRouteDao) {
        this.dataAccessor = dataAccessor;
        this.adRouteDao = adRouteDao;
    }

    @Override
    public UUID create(Bgp bgp) throws StateAccessException {
        return dataAccessor.create(bgp.toConfig());
    }

    @Override
    public void delete(UUID id) throws StateAccessException {
        dataAccessor.delete(id);
    }

    @Override
    public Bgp get(UUID id) throws StateAccessException {
        try {
            return new Bgp(id, dataAccessor.get(id));
        } catch (NoStatePathException e) {
            return null;
        }
    }

    @Override
    public void update(Bgp obj) throws StateAccessException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Bgp findByAdRoute(UUID adRouteId) throws StateAccessException {
        log.debug("BgpDaoImpl.findByAdRoute entered: adRouteId={}", adRouteId);

        AdRoute adRoute = adRouteDao.get(adRouteId);
        Bgp bgp = get(adRoute.getBgpId());

        log.debug("BgpDaoImpl.findByAdRoute exiting: BGP={}", bgp);
        return bgp;
    }

    @Override
    public List<Bgp> findByPort(UUID portId) throws StateAccessException {
        List<Bgp> bgps = new ArrayList<Bgp>();
        List<UUID> ids = dataAccessor.list(portId);
        for (UUID id : ids) {
            bgps.add(new Bgp(id, dataAccessor.get(id)));
        }
        return bgps;
    }
}
