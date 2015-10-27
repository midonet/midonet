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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import com.google.inject.Inject;
import com.google.protobuf.TextFormat;

import rx.Observable;
import rx.functions.Func1;

import org.midonet.cluster.data.storage.SingleValueKey;
import org.midonet.cluster.data.storage.StateKey;
import org.midonet.cluster.data.storage.StateResult;
import org.midonet.cluster.models.Commons.Condition;
import org.midonet.cluster.models.State;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.models.Topology.Host;
import org.midonet.cluster.services.MidonetBackend;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import static org.midonet.cluster.models.State.HostState.Interface;
import static org.midonet.cluster.models.State.HostState.newBuilder;
import static org.midonet.cluster.services.MidonetBackend.AliveKey;
import static org.midonet.cluster.services.MidonetBackend.HostKey;
import static org.midonet.cluster.util.IPAddressUtil.toIpAdresses;
import static org.midonet.cluster.util.UUIDUtil.fromProto;
import static org.midonet.cluster.util.UUIDUtil.toProto;
import static scala.collection.JavaConversions.asJavaIterable;

public class ZoomTopologyBackdoor implements TopologyBackdoor {

    @Inject
    MidonetBackend backend;

    private static final Duration TIMEOUT = Duration.apply(5, TimeUnit.SECONDS);

    public String getNamespace() {
        return backend.stateStore().namespace();
    }

    @Override
    public void addArpTableEntryToRouter(UUID routerId, IPv4Addr ip, MAC mac) {
        // Implementing this is just pointless in v2.  This is just used in
        // a single test that verified that a Router that had ARP entries
        // could be deleted correctly as they resided as children of the
        // Router path in ZK.  In v2, the ARP table is written in a whole
        // different tree than the Router, so this test doesn't really verify
        // anything.  We add an Assume clause to the test, so this code should
        // never be hit.
        throw new UnsupportedOperationException();
    }

    @Override
    public void createHost(UUID hostId, String name, InetAddress[] addresses) {
        Host h = Host.newBuilder()
                     .setId(toProto(hostId))
                     .setName(name)
                     .build();
        backend.store().create(h);
        try {
            Await.result(backend.store().get(h.getClass(), hostId), TIMEOUT);
            backend.stateStore().addValue(Host.class, hostId, HostKey(),
                                          State.HostState
                                              .getDefaultInstance()
                                              .toString())
                                .toBlocking().first();
        } catch (Exception e) {
            throw new RuntimeException("Could not create host", e);
        }
    }

    @Override
    public void makeHostAlive(UUID hostId) {
        State.HostState hostState = newBuilder()
            .setHostId(toProto(hostId))
            .build();
        backend.stateStore().addValue(Host.class, hostId,
                                      AliveKey(),
                                      hostState.toString())
                            .toBlocking().first();
    }

    @Override
    public List<UUID> getHostIds() {
        try {
            scala.collection.Seq<Host> hosts = Await.result(
                backend.store().getAll(Host.class), TIMEOUT);
            List<UUID> ids = new ArrayList<>();
            for (Host h : asJavaIterable(hosts)) {
                ids.add(fromProto(h.getId()));
            }
            return ids;
        } catch (Exception e) {
            throw new RuntimeException("Cannot get host ids", e);
        }
    }

    @Override
    public Integer getFloodingProxyWeight(UUID hostId) {
        try {
            return Await.result(
                backend.store().get(Host.class, toProto(hostId)), TIMEOUT)
                               .getFloodingProxyWeight();
            // for backwards compatibility
        } catch (Exception e) {
            throw new RuntimeException("Can't retrieve flooding proxy weight");
        }
    }

    @Override
    public void setFloodingProxyWeight(UUID hostId, int weight) {
        try {
            Host h = Await.result(
                backend.store().get(Host.class, toProto(hostId)), TIMEOUT);
            h = h.toBuilder().setFloodingProxyWeight(weight).build();
            backend.store().update(h);
        } catch (Exception e) {
            throw new RuntimeException("Can't retrieve flooding proxy weight");
        }
    }

    @Override
    public void createInterface(final UUID hostId, final String name,
                                final MAC mac, final int mtu,
                                final InetAddress[] addresses) {
        Observable<StateKey> o = backend.stateStore().getKey(Host.class,
                                                             hostId, HostKey());
        o.flatMap(new Func1<StateKey, Observable<StateResult>>() {
                   @Override
                   public Observable<StateResult> call(StateKey stateKey) {
                       SingleValueKey k = (SingleValueKey) stateKey;
                       State.HostState.Builder bldr = newBuilder();
                       if (k.value().nonEmpty()) {
                           try {
                               TextFormat.merge(k.value().get(), bldr);
                           } catch (Exception e) {
                               return Observable.error(e);
                           }
                       }
                       String hostState = bldr.addInterfaces(
                           Interface.newBuilder()
                               .setName(name)
                               .setMac(mac.toString())
                               .setType(Interface.Type.PHYSICAL)
                               .setMtu(mtu)
                               .addAllAddresses(toIpAdresses(addresses))
                               .build()
                       ).build().toString();
                       return backend.stateStore()
                                     .addValue(Host.class, hostId,
                                               HostKey(), hostState);
                   }
               }).toBlocking().first();
    }

    @Override
    public void addVirtualPortMapping(UUID hostId, UUID portId,
                                      String ifcName) {
        try {

            Topology.Port port = Await.result(backend.store().get(
                Topology.Port.class, portId), TIMEOUT);
            port = port.toBuilder()
                       .setHostId(toProto(hostId))
                       .setInterfaceName(ifcName)
                       .build();

            backend.store().update(port);
        } catch (Exception e) {
            throw new RuntimeException("Failed to add vrn port mapping");
        }
    }

    @Override
    public Topology.Host getHost(UUID hostId) {
        try {
            return Await.result(backend.store().get(Topology.Host.class,
                                                    hostId), TIMEOUT);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get host" + hostId);
        }
    }

    @Override
    public void delVirtualPortMapping(UUID hostId, UUID portId) {
        try {

            Topology.Port port = Await.result(backend.store().get(
                Topology.Port.class, portId), TIMEOUT);
            port = port.toBuilder()
                       .clearHostId()
                       .clearInterfaceName()
                       .build();

            backend.store().update(port);
        } catch (Exception e) {
            throw new RuntimeException("Failed to remove vrn port mapping");
        }
    }

    @Override
    public UUID createChain() {
        try {
            UUID randId = UUID.randomUUID();
            Topology.Chain c = Topology.Chain.getDefaultInstance()
                                             .toBuilder()
                                             .setId(UUIDUtil.toProto(randId))
                                             .build();
            backend.store().create(c);
            return randId;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create chain");
        }
    }

    @Override
    public UUID createRule(UUID chainId, short ethertype) {
        try {
            UUID randId = UUID.randomUUID();
            Topology.Rule r = Topology.Rule.getDefaultInstance()
                .toBuilder()
                .setId(UUIDUtil.toProto(randId))
                .setType(Topology.Rule.Type.LITERAL_RULE)
                .setAction(Topology.Rule.Action.ACCEPT)
                .setChainId(UUIDUtil.toProto(chainId))
                .setCondition(Condition.newBuilder().setDlType(ethertype))
                .build();
            backend.store().create(r);
            return randId;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create chain");
        }
    }
}
