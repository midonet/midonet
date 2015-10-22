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

import org.midonet.nsdb.BaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.rest_api.neutron.models.Member;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.l4lb.LBStatus;

/**
 * Class to manage the PoolMember ZooKeeper data.
 */
public class PoolMemberZkManager extends
        AbstractZkManager<UUID, PoolMemberZkManager.PoolMemberConfig> {

    public static class PoolMemberConfig extends BaseConfig {

        public UUID poolId;
        public String address;
        public int protocolPort;
        public int weight;
        public boolean adminStateUp;
        public LBStatus status;

        public PoolMemberConfig() {
            super();
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(poolId, address, protocolPort, weight,
                    adminStateUp, status);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || !getClass().equals(o.getClass()))
                return false;

            PoolMemberConfig that = (PoolMemberConfig) o;

            return Objects.equal(poolId, that.poolId) &&
                    Objects.equal(address, that.address) &&
                    protocolPort == that.protocolPort &&
                    weight == that.weight &&
                    adminStateUp == that.adminStateUp &&
                    status == that.status;
        }
    }

    public PoolMemberZkManager(ZkManager zk, PathBuilder paths,
                               Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getPoolMemberPath(id);
    }

    @Override
    protected Class<PoolMemberConfig> getConfigClass() {
        return PoolMemberConfig.class;
    }

}
