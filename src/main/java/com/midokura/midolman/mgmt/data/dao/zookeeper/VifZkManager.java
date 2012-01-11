/*
 * @(#)VifZkManager        1.6 18/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.OwnerQueryable;
import com.midokura.midolman.mgmt.data.dao.VifDao;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.Vif;
import com.midokura.midolman.mgmt.data.dto.config.VifConfig;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * ZK VIF management class.
 *
 * @version 1.6 18 Sept 2011
 * @author Ryu Ishimoto
 */
public class VifZkManager extends ZkMgmtManager implements VifDao,
        OwnerQueryable {

    private final static Logger log = LoggerFactory
            .getLogger(VifZkManager.class);

    public VifZkManager(Directory zk, String basePath, String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
    }

    public List<Op> prepareCreate(Vif vif) throws StateAccessException,
            ZkStateSerializationException {
        if (vif.getPortId() == null) {
            throw new IllegalArgumentException(
                    "VIF must be plugged into a port: " + vif.getId());
        }

        PortZkManagerProxy portZkManager = new PortZkManagerProxy(zk,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());

        Port port = null;
        if (!portZkManager.exists(vif.getPortId())) {
            throw new IllegalArgumentException("Invalid port UUID passed in:"
                    + vif.getPortId());
        }
        port = portZkManager.get(vif.getPortId());
        if (port.getVifId() != null) {
            throw new IllegalArgumentException(
                    "The port is already plugged into VIF: " + port.getVifId());
        }

        List<Op> ops = new ArrayList<Op>();
        String vifPath = mgmtPathManager.getVifPath(vif.getId());
        log.debug("Preparing to create: " + vifPath);
        try {
            ops.add(Op.create(vifPath, serialize(vif.toConfig()),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize VifConfig", e, VifConfig.class);
        }

        // Update the port with this VIF(should check the version here)
        port.setVifId(vif.getId());
        ops.addAll(portZkManager.prepareVifAttach(port));
        return ops;
    }

    public List<Op> prepareDelete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        return prepareDelete(get(id));
    }

    public List<Op> prepareDelete(Vif vif) throws StateAccessException,
            ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        String vifPath = mgmtPathManager.getVifPath(vif.getId());

        PortZkManagerProxy portZkManager = new PortZkManagerProxy(zk,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());
        if (vif.getPortId() != null) {
            ops.addAll(portZkManager.prepareVifDettach(vif.getPortId()));
        }

        log.debug("Preparing to delete: " + vifPath);
        ops.add(Op.delete(vifPath, -1));
        return ops;
    }

    @Override
    public UUID create(Vif vif) throws StateAccessException {
        if (null == vif.getId()) {
            vif.setId(UUID.randomUUID());
        }
        multi(prepareCreate(vif));
        return vif.getId();
    }

    @Override
    public void delete(UUID id) throws StateAccessException {
        multi(prepareDelete(id));
    }

    @Override
    public Vif get(UUID id) throws StateAccessException {
        byte[] data = get(mgmtPathManager.getVifPath(id), null);
        VifConfig config = null;
        try {
            config = deserialize(data, VifConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize VIF " + id + " to VifConfig", e,
                    VifConfig.class);
        }
        return new Vif(id, config.portId);
    }

    @Override
    public String getOwner(UUID id) throws StateAccessException {
        Vif vif = get(id);
        OwnerQueryable manager = new PortZkManagerProxy(zk,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());
        if (vif.getPortId() == null) {
            return null;
        }
        return manager.getOwner(vif.getPortId());
    }

    @Override
    public List<Vif> list() throws StateAccessException {
        List<Vif> result = new ArrayList<Vif>();
        Set<String> vifIds = getChildren(mgmtPathManager.getVifsPath(), null);
        for (String vifId : vifIds) {
            // For now, get each one.
            result.add(get(UUID.fromString(vifId)));
        }
        return result;
    }
}
