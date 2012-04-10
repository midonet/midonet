/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.AdRouteDao;
import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.state.BgpZkManager;
import com.midokura.midolman.state.BgpZkManager.BgpConfig;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Data access class for BGP.
 *
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
public class BgpZkProxy implements BgpDao {

    private final static Logger log = LoggerFactory.getLogger(BgpZkProxy.class);
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
    public BgpZkProxy(BgpZkManager dataAccessor, AdRouteDao adRouteDao) {
        this.dataAccessor = dataAccessor;
        this.adRouteDao = adRouteDao;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.BgpDao#create(com.midokura.midolman
     * .mgmt.data.dto.Bgp)
     */
    @Override
    public UUID create(Bgp bgp) throws StateAccessException {
        return dataAccessor.create(bgp.toConfig());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.BgpDao#delete(java.util.UUID)
     */
    @Override
    public void delete(UUID id) throws StateAccessException {
        dataAccessor.delete(id);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.BgpDao#get(java.util.UUID)
     */
    @Override
    public Bgp get(UUID id) throws StateAccessException {
        try {
            return new Bgp(id, dataAccessor.get(id).value);
        } catch (NoStatePathException e) {
            return null;
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.BgpDao#getByAdRoute(java.util.UUID)
     */
    @Override
    public Bgp getByAdRoute(UUID adRouteId) throws StateAccessException {
        log.debug("BgpZkProxy.getByAdRouteId entered: adRouteId={}",
                adRouteId);

        AdRoute adRoute = adRouteDao.get(adRouteId);
        Bgp bgp = get(adRoute.getBgpId());

        log.debug("BgpZkProxy.getByAdRouteId exiting: BGP={}", bgp);
        return bgp;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.BgpDao#list(java.util.UUID)
     */
    @Override
    public List<Bgp> list(UUID portId) throws StateAccessException {
        List<Bgp> bgps = new ArrayList<Bgp>();
        List<ZkNodeEntry<UUID, BgpConfig>> entries = dataAccessor.list(portId);
        for (ZkNodeEntry<UUID, BgpConfig> entry : entries) {
            bgps.add(new Bgp(entry.key, entry.value));
        }
        return bgps;
    }
}
