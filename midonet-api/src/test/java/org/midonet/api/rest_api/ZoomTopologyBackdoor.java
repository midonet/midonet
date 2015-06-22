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

package org.midonet.api.rest_api;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import scala.Function1;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import com.google.inject.Inject;
import com.google.protobuf.TextFormat;

import rx.Observable;
import rx.functions.Func1;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import org.midonet.cluster.data.storage.SingleValueKey;
import org.midonet.cluster.data.storage.StateKey;
import org.midonet.cluster.data.storage.StateResult;
import org.midonet.cluster.models.*;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.models.Topology.Host;
import org.midonet.cluster.services.MidonetBackend;
import org.midonet.cluster.services.rest_api.resources.HostInterfacePortResource;
import org.midonet.cluster.services.rest_api.resources.MidonetResource;
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

    @Override
    public void addArpTableEntryToRouter(UUID routerId, IPv4Addr ip, MAC mac) {
        throw new NotImplementedException();
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
    public void setHostVersion(UUID hostId) {
        throw new NotImplementedException();
    }
}
