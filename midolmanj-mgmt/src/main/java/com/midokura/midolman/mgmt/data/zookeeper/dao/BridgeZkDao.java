/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.Op;

import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.state.StateAccessException;

/**
 * Bridge ZK DAO interface.
 */
public interface BridgeZkDao extends BridgeDao {

    /**
     * Generates a list of ZK Op objects for deletion.
     *
     * @param id
     *            ID of the bridge
     * @return List of Op objects
     * @throws StateAccessException
     */
    List<Op> prepareDelete(UUID id) throws StateAccessException;

    /**
     * Generates a list of Op objects to delete a BridgeConfig.
     *
     * @param bridge
     *            Bridge object
     * @return List of Op objects
     * @throws StateAccessException
     */
    List<Op> prepareDelete(Bridge bridge) throws StateAccessException;

}
