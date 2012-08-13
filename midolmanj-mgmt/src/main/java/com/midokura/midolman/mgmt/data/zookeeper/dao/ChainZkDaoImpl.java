/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.RuleDao;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.mgmt.data.dto.config.ChainNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.zkManagers.ChainZkManager;
import com.midokura.midolman.state.zkManagers.ChainZkManager.ChainConfig;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkConfigSerializer;

/**
 * Chain ZK DAO adapter.
 */
public class ChainZkDaoImpl implements ChainZkDao {

    private final static Logger log = LoggerFactory
            .getLogger(ChainZkDaoImpl.class);
    private final ChainZkManager zkDao;
    private final PathBuilder pathBuilder;
    private final ZkConfigSerializer serializer;
    private final RuleDao ruleDao;

    /**
     * Constructor
     *
     * @param zkDao
     *            ChainZkManager object.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     * @param serializer
     *            ZkConfigSerializer object.
     * @param ruleDao
     *            RuleDao object.
     */
    public ChainZkDaoImpl(ChainZkManager zkDao, PathBuilder pathBuilder,
            ZkConfigSerializer serializer, RuleDao ruleDao) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
        this.ruleDao = ruleDao;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.zookeeper.ChainZkDao#prepareDelete
     * (java.util.UUID)
     */
    @Override
    public List<Op> prepareDelete(UUID id) throws StateAccessException {
        return prepareDelete(get(id));
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.zookeeper.ChainZkDao#prepareDelete
     * (java.util.UUID, com.midokura.midolman.state.zkManagers.ChainZkManager.ChainConfig)
     */
    @Override
    public List<Op> prepareDelete(Chain chain) throws StateAccessException {

        List<Op> ops = zkDao.prepareChainDelete(chain.getId());

        String path = pathBuilder.getTenantChainNamePath(chain.getTenantId(),
                chain.getName());
        ops.add(zkDao.getDeleteOp(path));

        return ops;
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
        log.debug("ChainZkDaoImpl.create entered: chain={}", chain);

        if (null == chain.getId()) {
            chain.setId(UUID.randomUUID());
        }

        List<Op> ops = zkDao
                .prepareChainCreate(chain.getId(), chain.toConfig());

        byte[] data = serializer.serialize(chain.toNameMgmtConfig());
        ops.add(zkDao.getPersistentCreateOp(
                pathBuilder.getTenantChainNamePath(chain.getTenantId(),
                        chain.getName()), data));

        zkDao.multi(ops);

        log.debug("ChainZkDaoImpl.create exiting: chain={}", chain);
        return chain.getId();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.ChainDao#delete(java.util.UUID)
     */
    @Override
    public void delete(UUID id) throws StateAccessException {
        log.debug("ChainZkDaoImpl.delete entered: id={}", id);

        List<Op> ops = prepareDelete(id);
        zkDao.multi(ops);

        log.debug("ChainZkDaoImpl.delete exiting.");
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.ChainDao#get(java.util.UUID)
     */
    @Override
    public Chain get(UUID id) throws StateAccessException {
        log.debug("ChainZkDaoImpl.get entered: id={}", id);

        Chain chain = null;
        if (zkDao.exists(id)) {
            ChainConfig config = zkDao.get(id);
            chain = new Chain(id, config);
        }

        log.debug("ChainZkDaoImpl.get existing: chain={}", chain);
        return chain;
    }

    @Override
    public void update(Chain obj) throws StateAccessException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Chain findByName(String tenantId, String name)
            throws StateAccessException {
        log.debug("ChainZkDaoImpl.findByName entered: tenantId=" + tenantId
                + ", name=" + name);

        Chain chain = null;
        String path = pathBuilder.getTenantChainNamePath(tenantId, name);
        if (zkDao.exists(path)) {
            byte[] data = zkDao.get(path);
            ChainNameMgmtConfig nameConfig = serializer.deserialize(data,
                    ChainNameMgmtConfig.class);
            chain = get(nameConfig.id);
        }

        log.debug("ChainZkDaoImpl.findByName existing: chain={}", chain);
        return chain;
    }

    @Override
    public Chain findByRule(UUID ruleId) throws StateAccessException {
        log.debug("ChainZkDaoImpl.findByRule entered: ruleId={}", ruleId);

        Rule rule = ruleDao.get(ruleId);
        Chain chain = get(rule.getChainId());

        log.debug("ChainZkDaoImpl.findByRule exiting: chain={}", chain);
        return chain;
    }

    @Override
    public List<Chain> findByTenant(String tenantId)
            throws StateAccessException {
        log.debug("ChainZkDaoImpl.findByTenant entered: tenantId={}", tenantId);

        String path = pathBuilder.getTenantChainNamesPath(tenantId);
        Set<String> names = zkDao.getChildren(path, null);
        List<Chain> chains = new ArrayList<Chain>();
        for (String name : names) {
            chains.add(findByName(tenantId, name));
        }

        log.debug("ChainZkDaoImpl.findByTenant exiting: num of chains={}",
                chains.size());
        return chains;
    }
}
