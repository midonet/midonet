/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.Op;

import com.midokura.midolman.mgmt.data.dao.PortGroupDao;
import com.midokura.midolman.mgmt.data.dto.PortGroup;
import com.midokura.midolman.state.StateAccessException;

/**
 * Port Group ZK DAO interface.
 */
public interface PortGroupZkDao extends PortGroupDao {

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
     * Generates a list of Op objects to delete a PortGroup.
     *
     * @param router
     *            Router object
     * @return List of Op objects
     * @throws StateAccessException
     */
    List<Op> prepareDelete(PortGroup portGroup) throws StateAccessException;

}
