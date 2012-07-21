/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.Op;

import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.state.StateAccessException;

/**
 * Router ZK DAO interface.
 */
public interface RouterZkDao extends RouterDao {

    /**
     * Generates a list of ZK Op objects for deletion.
     *
     * @param id
     *            ID of the router
     * @return List of Op objects
     * @throws StateAccessException
     */
    List<Op> prepareDelete(UUID id) throws StateAccessException;

    /**
     * Generates a list of Op objects to delete a RouterConfig.
     *
     * @param router
     *            Router object
     * @return List of Op objects
     * @throws StateAccessException
     */
    List<Op> prepareDelete(Router router) throws StateAccessException;

}
