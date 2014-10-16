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

package org.midonet.cluster.data.l4lb;

import org.midonet.cluster.data.Entity;

import java.util.Objects;
import java.util.UUID;

public class LoadBalancer
        extends Entity.Base<UUID, LoadBalancer.Data, LoadBalancer> {

    public LoadBalancer() {
        this(null, new Data());
    }

    public LoadBalancer(UUID id){
        this(id, new Data());
    }

    public LoadBalancer(Data data) {
        this(null, data);
    }

    public LoadBalancer(UUID id, Data data) {
        super(id, data);
    }

    @Override
    protected LoadBalancer self() {
        return this;
    }

    public LoadBalancer setAdminStateUp(boolean adminStateUp) {
        getData().adminStateUp = adminStateUp;
        return self();
    }

    public boolean isAdminStateUp() {
        return getData().adminStateUp;
    }

    public LoadBalancer setRouterId(UUID routerId) {
        getData().routerId = routerId;
        return self();
    }

    public UUID getRouterId() {
        return getData().routerId;
    }

    public static class Data {
        private UUID routerId;
        private boolean adminStateUp = true;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass())
                return false;
            Data data = (Data) o;

            return adminStateUp == data.adminStateUp;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(adminStateUp);
        }

        @Override
        public String toString() {
            return "Data{" +
                    "routerId=" + routerId +
                    ", adminStateUp=" + adminStateUp +
                    '}';
        }
    }
}
