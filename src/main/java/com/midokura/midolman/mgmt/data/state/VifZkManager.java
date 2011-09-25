/*
 * @(#)VifZkManager        1.6 18/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.Vif;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * ZK VIF management class.
 * 
 * @version 1.6 18 Sept 2011
 * @author Ryu Ishimoto
 */
public class VifZkManager extends ZkMgmtManager {

    public static class VifConfig {

        public VifConfig() {
            super();
        }

        public VifConfig(String name) {
            super();
            this.name = name;
        }

        public VifConfig(UUID portId, String name) {
            super();
            this.portId = portId;
            this.name = name;
        }

        public UUID portId;
        public String name;
    }

    private final static Logger log = LoggerFactory
            .getLogger(VifZkManager.class);

    public VifZkManager(ZooKeeper zk, String basePath, String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
    }

    public List<Op> prepareCreate(Vif vif) throws StateAccessException,
            ZkStateSerializationException {
        PortZkManagerProxy portZkManager = new PortZkManagerProxy(zooKeeper,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());

        Port port = null;
        if (vif.getPortId() != null) {
            if (!portZkManager.exists(vif.getPortId())) {
                throw new IllegalArgumentException(
                        "Invalid port UUID passed in:" + vif.getPortId());
            }
            port = portZkManager.get(vif.getPortId());
            if (port.getVifId() != null) {
                throw new IllegalArgumentException(
                        "The port is already plugged into VIF: "
                                + port.getVifId());
            }
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
        if (port != null) {
            ops.addAll(portZkManager.prepareVifAttach(port));
        }
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

        PortZkManagerProxy portZkManager = new PortZkManagerProxy(zooKeeper,
                pathManager.getBasePath(), mgmtPathManager.getBasePath());
        if (vif.getPortId() != null) {
            portZkManager.prepareVifDettach(vif.getPortId());
        }

        log.debug("Preparing to delete: " + vifPath);
        ops.add(Op.delete(vifPath, -1));
        return ops;
    }

    public UUID create(Vif vif) throws StateAccessException,
            ZkStateSerializationException {
        if (null == vif.getId()) {
            vif.setId(UUID.randomUUID());
        }
        multi(prepareCreate(vif));
        return vif.getId();
    }

    public void delete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        multi(prepareDelete(id));
    }

    public Vif get(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        byte[] data = get(mgmtPathManager.getVifPath(id), null);
        VifConfig config = null;
        try {
            config = deserialize(data, VifConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize VIF " + id + " to VifConfig", e,
                    VifConfig.class);
        }
        return Vif.createVif(config);
    }
}
