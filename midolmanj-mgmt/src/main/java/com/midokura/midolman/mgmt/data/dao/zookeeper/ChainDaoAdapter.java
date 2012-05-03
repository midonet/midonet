/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dao.RuleDao;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.mgmt.data.dto.config.ChainMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.ChainNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.op.ChainOpService;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;
import com.midokura.midolman.state.StateAccessException;

/**
 * Chain ZK DAO adapter.
 */
public class ChainDaoAdapter implements ChainDao {

    private final static Logger log = LoggerFactory
            .getLogger(ChainDaoAdapter.class);
    private final ChainZkDao zkDao;
    private final ChainOpService opService;
    private final RuleDao ruleDao;

    /**
     * Constructor
     *
     * @param zkDao
     *            ChainZkDao object.
     * @param opService
     *            ChainOpService object.
     * @param ruleDao
     *            RuleDao object.
     */
    public ChainDaoAdapter(ChainZkDao zkDao, ChainOpService opService,
            RuleDao ruleDao) {
        this.zkDao = zkDao;
        this.opService = opService;
        this.ruleDao = ruleDao;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.ChainDao#create(com.midokura.midolman
     * .mgmt.data.dto.Chain)
     */
    @Override
    public UUID create(Chain chain) throws StateAccessException {
        log.debug("ChainDaoAdapter.create entered: chain={}", chain);

        if (null == chain.getId()) {
            chain.setId(UUID.randomUUID());
        }

        List<Op> ops = opService.buildCreate(chain.getId(), chain.toConfig(),
                chain.toMgmtConfig(), chain.toNameMgmtConfig());
        zkDao.multi(ops);

        log.debug("ChainDaoAdapter.create exiting: chain={}", chain);
        return chain.getId();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.ChainDao#delete(java.util.UUID)
     */
    @Override
    public void delete(UUID id) throws StateAccessException {
        log.debug("ChainDaoAdapter.delete entered: id={}", id);

        Chain chain = get(id);
        List<Op> ops = opService.buildDelete(chain.getId(), true);
        zkDao.multi(ops);

        log.debug("ChainDaoAdapter.delete exiting.");
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.ChainDao#get(java.util.UUID)
     */
    @Override
    public Chain get(UUID id) throws StateAccessException {
        log.debug("ChainDaoAdapter.get entered: id={}", id);

        Chain chain = null;
        if (zkDao.exists(id)) {
            ChainMgmtConfig mgmtConfig = zkDao.getMgmtData(id);
            ChainConfig config = zkDao.getData(id);
            chain = new Chain(id, mgmtConfig.tenantId, mgmtConfig.name);
        }

        log.debug("ChainDaoAdapter.get existing: chain={}", chain);
        return chain;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.ChainDao#get(java.util.UUID,
     * java.lang.String)
     */
    @Override
    public Chain get(UUID routerId, String name)
            throws StateAccessException {
        log.debug("ChainDaoAdapter.get entered: routerId=" + routerId
                + ", name=" + name);

        ChainNameMgmtConfig nameConfig = zkDao.getNameData(routerId, name);
        Chain chain = get(nameConfig.id);

        log.debug("ChainDaoAdapter.get existing: chain={}", chain);
        return chain;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.ChainDao#getByRule(java.util.UUID)
     */
    @Override
    public Chain getByRule(UUID ruleId) throws StateAccessException {
        log.debug("ChainDaoAdapter.getByRule entered: ruleId={}", ruleId);

        Rule rule = ruleDao.get(ruleId);
        Chain chain = get(rule.getChainId());

        log.debug("ChainDaoAdapter.getByRule exiting: chain={}", chain);
        return chain;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.ChainDao#list(java.util.UUID)
     */
    @Override
    public List<Chain> list(UUID tenantId) throws StateAccessException {
        log.debug("ChainDaoAdapter.list entered: tenantId={}", tenantId);

        Set<String> ids = new TreeSet<String>();
        ids.addAll(zkDao.getIds(tenantId));

        List<Chain> chains = new ArrayList<Chain>();
        for (String id : ids) {
            chains.add(get(UUID.fromString(id)));
        }
        return chains;
    }
}
