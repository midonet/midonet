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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.nsdb.ConfigWithProperties;

/**
 * Class to manage the router ZooKeeper data.
 */
public class PortGroupZkManager
        extends AbstractZkManager<UUID, PortGroupZkManager.PortGroupConfig> {

    private final static Logger log = LoggerFactory
            .getLogger(PortGroupZkManager.class);

    public static class PortGroupConfig extends ConfigWithProperties {

        public PortGroupConfig() {
            super();
        }

        public String name;
        public boolean stateful;
    }

    /**
     * Initializes a PortGroupZkManager object with a ZooKeeper client and the
     * root path of the ZooKeeper directory.
     */
    public PortGroupZkManager(ZkManager zk, PathBuilder paths,
                              Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getPortGroupPath(id);
    }

    @Override
    protected Class<PortGroupConfig> getConfigClass() {
        return PortGroupConfig.class;
    }

}
