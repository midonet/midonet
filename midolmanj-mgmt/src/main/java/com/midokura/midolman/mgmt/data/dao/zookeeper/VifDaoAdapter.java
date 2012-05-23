/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.VifDao;
import com.midokura.midolman.mgmt.data.dto.Vif;
import com.midokura.midolman.mgmt.data.dto.config.VifConfig;
import com.midokura.midolman.mgmt.data.zookeeper.op.VifOpService;
import com.midokura.midolman.state.StateAccessException;

/**
 * VIF ZK DAO adapter
 */
public class VifDaoAdapter implements VifDao {

    private final static Logger log = LoggerFactory
            .getLogger(VifDaoAdapter.class);
    private final VifZkDao zkDao;
    private final VifOpService opService;

    /**
     * Constructor
     *
     * @param zkDao
     *            VifZkDao object
     * @param opService
     *            VifOpService object
     */
    public VifDaoAdapter(VifZkDao zkDao, VifOpService opService) {
        this.zkDao = zkDao;
        this.opService = opService;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.VifDao#create(com.midokura.midolman
     * .mgmt.data.dto.Vif)
     */
    @Override
    public UUID create(Vif vif) throws StateAccessException {
        log.debug("VifDaoAdapter.create entered: vif={}", vif);

        if (vif.getId() == null) {
            vif.setId(UUID.randomUUID());
        }

        // This will throw an exception if the port does not exist.
        // When the validation logic is implemented, handle these situations
        // better.
        List<Op> ops = opService.buildCreate(vif.getId(), vif.toConfig());
        zkDao.multi(ops);

        log.debug("VifDaoAdapter.create exiting: vif={}", vif);
        return vif.getId();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.VifDao#delete(java.util.UUID)
     */
    @Override
    public void delete(UUID id) throws StateAccessException {
        log.debug("VifDaoAdapter.delete entered: id={}", id);

        List<Op> ops = opService.buildDelete(id);
        zkDao.multi(ops);

        log.debug("VifDaoAdapter.delete exiting.");
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.VifDao#get(java.util.UUID)
     */
    @Override
    public Vif get(UUID id) throws StateAccessException {
        log.debug("VifDaoAdapter.get entered: id={}", id);

        Vif vif = null;
        if (zkDao.exists(id)) {
            VifConfig config = zkDao.getData(id);
            vif = new Vif(id, config.portId);
        }

        log.debug("VifDaoAdapter.get existing: vif={}", vif);
        return vif;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.VifDao#list()
     */
    @Override
    public List<Vif> list() throws StateAccessException {
        log.debug("VifDaoAdapter.list entered.");

        Set<String> ids = zkDao.getIds();
        List<Vif> vifs = new ArrayList<Vif>();
        for (String id : ids) {
            vifs.add(get(UUID.fromString(id)));
        }

        log.debug("VifDaoAdapter.list exiting: vifs count={}", vifs.size());
        return vifs;
    }
}