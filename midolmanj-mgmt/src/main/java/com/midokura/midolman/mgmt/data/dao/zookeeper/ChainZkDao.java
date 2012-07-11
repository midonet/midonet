/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.Op;

import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.state.StateAccessException;

/**
 * Chain ZK DAO interface.
 */
public interface ChainZkDao extends ChainDao {

    /**
     * Generates a list of ZK Op objects for deletion.
     *
     * @param id
     *            ID of the chain
     * @return List of Op objects
     * @throws StateAccessException
     */
    List<Op> prepareDelete(UUID id) throws StateAccessException;

    /**
     * Generates a list of Op objects to delete a ChainConfig.
     *
     * @param chain
     *            Chain object
     * @return List of Op objects
     * @throws StateAccessException
     */
    List<Op> prepareDelete(Chain chain) throws StateAccessException;

}