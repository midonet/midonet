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

package org.midonet.cluster.rest_api.rest_api;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

import org.midonet.cluster.models.*;
import org.midonet.cluster.models.Topology;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

/**
 * This interface provides a backdoor into the backend storage that can be
 * used to perform some operations directly on the underlying storage, in
 * order to verify conditions through the API.
 *
 * You should only add methods on this interface IF AND ONLY IF there is not
 * a way to do it through the REST API. For example, do NOT add a method
 * here to create a Bridge, as you can create a Bridge through the REST API.
 *
 * Also, make sure not to leak any backend classes out to the REST API tests.
 * For example, make all parameters primitives (UUID, etc.) but do NOT make
 * methods use backend models. See that existing methods do not pass models
 * such as ArpTableEntry, or a Host, but instead pass the values and let the
 * backend implementation figure out the models.
 *
 * Also, avoid bubbling up backend-specific exeptions, as these will couple the
 * API to backend implementations. Wrap them in a RuntimeException before.
 */
public interface TopologyBackdoor {

    String getNamespace();

    void addArpTableEntryToRouter(UUID routerId, IPv4Addr ip, MAC mac);

    void createHost(UUID hostId, String name, InetAddress[] addresses);

    void makeHostAlive(UUID hostId);

    List<UUID> getHostIds();

    Integer getFloodingProxyWeight(UUID hostId);

    void setFloodingProxyWeight(UUID hostId, int weight);

    void createInterface(UUID hostId, String name, MAC mac, int mtu,
                                InetAddress[] addresses);

    void addVirtualPortMapping(UUID hostId, UUID portId, String ifcName);

    void delVirtualPortMapping(UUID hostId, UUID portId);

    Topology.Host getHost(UUID hostId);

    UUID createChain();

    UUID createRule(UUID chainId, short ethertype);

}
