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

import com.google.common.base.Objects;
import com.google.inject.Inject;

import java.util.List;
import java.util.UUID;
import org.apache.zookeeper.Op;

import org.midonet.cluster.rest_api.neutron.models.SessionPersistenceType;
import org.midonet.cluster.rest_api.neutron.models.VIP;
import org.midonet.midolman.state.l4lb.VipSessionPersistence;
import org.midonet.nsdb.BaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;

import static java.util.Arrays.asList;

/**
 * Class to manage the VIP ZooKeeper data.
 */
public class VipZkManager
        extends AbstractZkManager<UUID, VipZkManager.VipConfig> {

    public static class VipConfig extends BaseConfig {
        public UUID loadBalancerId;
        public UUID poolId;
        public String address;
        public int protocolPort;
        public VipSessionPersistence sessionPersistence;
        public boolean adminStateUp;

        public VipConfig() {
            super();
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(loadBalancerId, poolId, address,
                    protocolPort, sessionPersistence, adminStateUp);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || !getClass().equals(o.getClass()))
                return false;

            VipConfig that = (VipConfig) o;

            return Objects.equal(loadBalancerId, that.loadBalancerId) &&
                    Objects.equal(poolId, that.poolId) &&
                    Objects.equal(address, that.address) &&
                    protocolPort == that.protocolPort &&
                    sessionPersistence == that.sessionPersistence &&
                    adminStateUp == that.adminStateUp;
        }
    }

    @Inject
    public VipZkManager(ZkManager zk, PathBuilder paths,
                        Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    public Class<VipConfig> getConfigClass() {
        return VipConfig.class;
    }

    @Override
    public String getConfigPath(UUID id) {
        return paths.getVipPath(id);
    }

}
