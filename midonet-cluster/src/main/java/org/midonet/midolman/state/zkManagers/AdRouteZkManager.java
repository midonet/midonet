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

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

import com.google.inject.Inject;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.nsdb.BaseConfig;

public class AdRouteZkManager
        extends AbstractZkManager<UUID, AdRouteZkManager.AdRouteConfig> {

    public static final class AdRouteConfig extends BaseConfig {

        public InetAddress nwPrefix;
        public byte prefixLength;
        public UUID bgpId;

        public AdRouteConfig(UUID bgpId, InetAddress nwPrefix, byte prefixLength) {
            this.bgpId = bgpId;
            this.nwPrefix = nwPrefix;
            this.prefixLength = prefixLength;
        }

        // Default constructor for the Jackson deserialization.
        public AdRouteConfig() {
            super();
        }
    }

    @Inject
    public AdRouteZkManager(ZkManager zk, PathBuilder paths,
                            Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getAdRoutePath(id);
    }

    @Override
    protected Class<AdRouteConfig> getConfigClass() {
        return AdRouteConfig.class;
    }

    public List<UUID> list(UUID bgpId, Runnable watcher)
            throws StateAccessException {
        return getUuidList(paths.getBgpAdRoutesPath(bgpId), watcher);
    }

    public List<UUID> list(UUID bgpId) throws StateAccessException {
        return this.list(bgpId, null);
    }

}
