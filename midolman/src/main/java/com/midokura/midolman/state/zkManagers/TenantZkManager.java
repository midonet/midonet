/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.state.zkManagers;

import com.google.inject.Inject;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PathBuilder;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkManager;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Zk DAO for tenants.  This class used purely by the REST API.
 */
public class TenantZkManager extends ZkManager  {

    private final static Logger log = LoggerFactory
            .getLogger(TenantZkManager.class);

    private final PathBuilder pathBuilder;

    public TenantZkManager(Directory zk, String basePath) {
        super(zk, basePath);
        this.pathBuilder = new PathBuilder(basePath);
    }

    public List<Op> prepareCreate(String tenantId) throws StateAccessException {

        List<Op> ops = new ArrayList<Op>();

        String tenantsPath = pathBuilder.getTenantsPath();
        if (!exists(tenantsPath)) {
            ops.add(Op.create(tenantsPath, null, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        }

        String tenantPath = pathBuilder.getTenantPath(tenantId);
        if (!exists(tenantPath)) {
            ops.add(Op.create(tenantPath, null, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        }

        return ops;
    }
}
