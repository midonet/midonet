package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.state.StateAccessException;

public interface RouteDao extends OwnerQueryable {

    UUID create(Route route) throws StateAccessException;

    Route get(UUID id) throws StateAccessException;

    List<Route> list(UUID routerId) throws StateAccessException;

    List<Route> listByPort(UUID portId) throws StateAccessException;

    void delete(UUID id) throws StateAccessException;
}
