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
import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;

import org.midonet.cluster.WatchableZkManager;
import org.midonet.cluster.data.VtepBinding;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.ZkPathManager;
import org.midonet.packets.IPv4Addr;

public class VtepZkManager
        extends AbstractZkManager<IPv4Addr, VtepZkManager.VtepConfig>
        implements WatchableZkManager<IPv4Addr, VtepZkManager.VtepConfig> {

    public static class VtepConfig {
        public int mgmtPort;
        public UUID tunnelZone;
    }

    @Inject
    public VtepZkManager(ZkManager zk, PathBuilder paths,
                         Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(IPv4Addr key) {
        return paths.getVtepPath(key);
    }

    @Override
    protected Class<VtepConfig> getConfigClass() {
        return VtepConfig.class;
    }

    public List<VtepBinding> getBindings(IPv4Addr ipAddr)
            throws StateAccessException {
        String bindingsPath = paths.getVtepBindingsPath(ipAddr);
        Set<String> children = zk.getChildren(bindingsPath);
        List<VtepBinding> bindings = new ArrayList<>(children.size());
        for (String child : children) {
            String[] parts = child.split("_", 3);
            if (parts.length != 3) {
                throw new IllegalStateException(
                        "Invalid binding key: " + child, null);
            }

            short vlanId = Short.parseShort(parts[0]);
            UUID networkId = UUID.fromString(parts[1]);
            String portName = ZkPathManager.decodePathSegment(parts[2]);
            bindings.add(new VtepBinding(portName, vlanId, networkId));
        }

        return bindings;
    }
}
