package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.state.StateAccessException;

public interface PortDao extends OwnerQueryable {
    Port get(UUID id) throws StateAccessException;

    List<Port> listRouterPorts(UUID routerId) throws StateAccessException;

    List<Port> listBridgePorts(UUID bridgeId) throws StateAccessException;

    UUID create(Port port) throws StateAccessException;

    boolean exists(UUID id) throws StateAccessException;

    void delete(UUID id) throws StateAccessException;
}
