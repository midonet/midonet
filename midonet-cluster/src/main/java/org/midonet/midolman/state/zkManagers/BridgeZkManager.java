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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.google.inject.Inject;

import org.midonet.cluster.WatchableZkManager;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.nsdb.ConfigWithProperties;


/**
 * Class to manage the bridge ZooKeeper data.
 */
public class BridgeZkManager
        extends AbstractZkManager<UUID, BridgeZkManager.BridgeConfig>
        implements WatchableZkManager<UUID, BridgeZkManager.BridgeConfig> {

    public static class BridgeConfig extends ConfigWithProperties {
        public BridgeConfig() {
            super();
        }

        // TODO: Make this private with a getter.
        public int tunnelKey; // Only set in prepareBridgeCreate
        public UUID inboundFilter;
        public UUID outboundFilter;
        public UUID vxLanPortId;
        public List<UUID> vxLanPortIds = new ArrayList<>(0);
        public String name;
        public boolean adminStateUp;

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            BridgeConfig that = (BridgeConfig) o;

            return tunnelKey == that.tunnelKey &&
                    adminStateUp == that.adminStateUp &&
                    Objects.equals(inboundFilter, that.inboundFilter) &&
                    Objects.equals(outboundFilter, that.outboundFilter) &&
                    Objects.equals(vxLanPortId, that.vxLanPortId) &&
                    Objects.equals(vxLanPortIds, that.vxLanPortIds) &&
                    Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tunnelKey, adminStateUp, inboundFilter,
                                outboundFilter, vxLanPortId, vxLanPortIds, name);
        }

        @Override
        public String toString() {
            return "BridgeConfig{tunnelKey=" + tunnelKey +
                   ", inboundFilter=" + inboundFilter +
                   ", outboundFilter=" + outboundFilter +
                   ", vxLanPortId=" + vxLanPortId +
                   ", vxLanPortIds=" + vxLanPortIds +
                   ", name=" + name +
                   ", adminStateUp=" + adminStateUp + '}';
        }
    }

    /**
     * Initializes a BridgeZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     */
    @Inject
    public BridgeZkManager(ZkManager zk, PathBuilder paths,
                           Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getBridgePath(id);
    }

    @Override
    protected Class<BridgeConfig> getConfigClass() {
        return BridgeConfig.class;
    }

    // This method creates the directory if it doesn't already exist,
    // because bridges may have been created before the ARP feature was added.
    public Directory getIP4MacMapDirectory(UUID id)
            throws StateAccessException {
        String path = paths.getBridgeIP4MacMapPath(id);
        if (exists(id) && !zk.exists(path))
            zk.addPersistent(path, null);

        return zk.getSubDirectory(path);
    }


    public Directory getMacPortMapDirectory(UUID id, short vlanId)
            throws StateAccessException{
        return zk.getSubDirectory(paths.getBridgeMacPortsPath(id, vlanId));
    }

}
