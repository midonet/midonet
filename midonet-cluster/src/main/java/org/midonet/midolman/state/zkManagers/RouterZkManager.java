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

import java.util.UUID;

import com.google.common.base.Objects;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.nsdb.ConfigWithProperties;


/**
 * Class to manage the router ZooKeeper data
 */
public class RouterZkManager
        extends AbstractZkManager<UUID, RouterZkManager.RouterConfig> {

    public static class RouterConfig extends ConfigWithProperties {

        public String name;
        public boolean adminStateUp;
        public UUID inboundFilter;
        public UUID outboundFilter;
        public UUID loadBalancer;

        public RouterConfig() {
            super();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            RouterConfig that = (RouterConfig) o;

            if (!Objects.equal(inboundFilter, that.inboundFilter))
                return false;
            if (!Objects.equal(outboundFilter, that.outboundFilter))
                return false;
            if (!Objects.equal(loadBalancer, that.loadBalancer))
                return false;
            if (!Objects.equal(name, that.name))
                return false;
            if (adminStateUp != that.adminStateUp)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(inboundFilter, outboundFilter, loadBalancer,
                                    name, adminStateUp);
        }
    }

    /**
     * Initializes a RouterZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     */
    public RouterZkManager(ZkManager zk, PathBuilder paths,
                           Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getRouterPath(id);
    }

    @Override
    protected Class<RouterConfig> getConfigClass() {
        return RouterConfig.class;
    }

}
