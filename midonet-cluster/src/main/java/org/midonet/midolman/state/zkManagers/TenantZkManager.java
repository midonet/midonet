/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.state.zkManagers;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Zk DAO for tenants.  This class used purely by the REST API.
 */
public class TenantZkManager extends BaseZkManager {

    /**
     * Constructor to set ZooKeeper and base path.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public TenantZkManager(ZkManager zk, PathBuilder paths,
                           Serializer serializer) {
        super(zk, paths, serializer);
    }

    public List<Op> prepareCreate(String tenantId) throws StateAccessException {

        List<Op> ops = new ArrayList<Op>();

        String tenantsPath = paths.getTenantsPath();
        if (!zk.exists(tenantsPath)) {
            ops.add(Op.create(tenantsPath, null, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        }

        String tenantPath = paths.getTenantPath(tenantId);
        if (!zk.exists(tenantPath)) {
            ops.add(Op.create(tenantPath, null, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        }

        return ops;
    }

    /**
     * Gets a list of all tenants.
     *
     * @return Set containing tenant IDs
     * @throws StateAccessException
     */
    public Set<String> list() throws StateAccessException {

        String tenantsPath = paths.getTenantsPath();
        if (zk.exists(tenantsPath)) {
            return zk.getChildren(tenantsPath);
        } else {
            return new HashSet<String>();
        }
    }
}
