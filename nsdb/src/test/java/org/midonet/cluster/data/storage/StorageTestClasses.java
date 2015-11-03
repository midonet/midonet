/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.data.storage;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class StorageTestClasses {

    protected static class PojoBridge {
        public UUID id;
        public String name;
        public UUID inChainId;
        public UUID outChainId;
        public List<UUID> portIds;

        PojoBridge() {}

        PojoBridge(UUID id, String name, UUID inChainId, UUID outChainId) {
            this.id = id;
            this.name = name;
            this.inChainId = inChainId;
            this.outChainId = outChainId;
        }

        PojoBridge(String name, UUID inChainId, UUID outChainId) {
            this(UUID.randomUUID(), name, inChainId, outChainId);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PojoBridge)) return false;
            PojoBridge pb = (PojoBridge)o;
            return Objects.equals(id, pb.id) &&
                   Objects.equals(name, pb.name) &&
                   Objects.equals(inChainId, pb.inChainId) &&
                   Objects.equals(outChainId, pb.outChainId) &&
                   Objects.equals(portIds, pb.portIds);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, name, inChainId, outChainId, portIds);
        }
    }

    protected static class PojoRouter {
        public UUID id;
        public String name;
        public UUID inChainId;
        public UUID outChainId;
        public List<UUID> portIds;

        PojoRouter() {}

        PojoRouter(UUID id, String name, UUID inChainId, UUID outChainId) {
            this.id = id;
            this.name = name;
            this.inChainId = inChainId;
            this.outChainId = outChainId;
        }

        PojoRouter(String name, UUID inChainId, UUID outChainId) {
            this(UUID.randomUUID(), name, inChainId, outChainId);
        }
    }

    protected static class PojoPort {
        public UUID id;
        public String name;
        public UUID peerId;
        public UUID bridgeId;
        public UUID routerId;
        public UUID inChainId;
        public UUID outChainId;
        public List<UUID> ruleIds;

        PojoPort() {}

        PojoPort(String name, UUID bridgeId, UUID routerId) {
            this(name, bridgeId, routerId, null, null, null);
        }

        PojoPort(String name, UUID bridgeId, UUID routerId,
                 UUID peerId, UUID inChainId, UUID outChainId) {
            this.id = UUID.randomUUID();
            this.name = name;
            this.peerId = peerId;
            this.bridgeId = bridgeId;
            this.routerId = routerId;
            this.inChainId = inChainId;
            this.outChainId = outChainId;
        }
    }

    protected static class PojoChain {
        public UUID id;
        public String name;
        public List<UUID> ruleIds;
        public List<UUID> bridgeIds;
        public List<UUID> routerIds;
        public List<UUID> portIds;

        PojoChain() {}

        public PojoChain(String name) {
            this.id = UUID.randomUUID();
            this.name = name;
        }
    }

    protected static class PojoRule {
        public UUID id;
        public UUID chainId;
        public String name;
        public List<UUID> portIds;
        public List<String> strings; // Needed for a declareBinding() test.

        PojoRule() {}

        public PojoRule(String name, UUID chainId, UUID... portIds) {
            this.id = UUID.randomUUID();
            this.name = name;
            this.chainId = chainId;
            this.portIds = Arrays.asList(portIds);
        }
    }

    protected static class NoIdField {
        public UUID notId;
        public List<UUID> refIds;
    }

    protected static class State {
        public UUID id = UUID.randomUUID();
    }

}
