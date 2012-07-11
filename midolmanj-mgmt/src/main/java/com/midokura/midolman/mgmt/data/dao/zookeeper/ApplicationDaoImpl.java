/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.ApplicationDao;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathService;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.StatePathExistsException;
import com.midokura.midolman.state.ZkManager;

/**
 * ZooKeeper data access manager for application-wide operations.
 */
public class ApplicationDaoImpl implements ApplicationDao {

    private final static Logger log = LoggerFactory
            .getLogger(ApplicationDaoImpl.class);
    private final ZkManager zkDao;
    private final PathService pathService;

    /**
     * Constructor
     *
     * @param zkDao
     *            ZooKeeper data access object.
     * @param pathService
     *            ZooKeeper path helper service.
     */
    public ApplicationDaoImpl(ZkManager zkDao, PathService pathService) {
        this.zkDao = zkDao;
        this.pathService = pathService;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.ApplicationDao#initialize()
     */
    @Override
    public void initialize() throws StateAccessException {
        log.debug("ApplicationDaoImpl.initialize entered.");

        Set<String> paths = pathService.getInitialPaths();
        for (String path : paths) {
            try {
                zkDao.addPersistent(path, null);
            } catch (StatePathExistsException e) {
                // Keep it idempotent.
                log.info("Already created: " + path);
            }
        }

        log.debug("ApplicationDaoImpl.initialize exiting.");
    }
}
