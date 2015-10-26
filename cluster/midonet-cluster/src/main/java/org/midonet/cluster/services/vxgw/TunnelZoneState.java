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
package org.midonet.cluster.services.vxgw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
import rx.subjects.ReplaySubject;
import rx.subjects.Subject;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityIdSetEvent;
import org.midonet.cluster.EntityIdSetMonitor;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.host.Host;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.packets.IPv4Addr;

/**
 * Maintains local state information for an existing tunnel-zone object.
 */
public class TunnelZoneState {

    private static final Logger log =
        LoggerFactory.getLogger(TunnelZoneState.class);

    /**
     * The flooding proxy operation.
     */
    public enum FloodingProxyOp {
        SET, CLEAR
    }

    /**
     * Represents an event for a change in the tunnel zone flooding proxy.
     */
    public final static class FloodingProxyEvent {
        public final FloodingProxyOp operation;
        public final UUID tunnelZoneId;
        public final HostStateConfig hostConfig;

        /**
         * Creates a new flooding proxy event instance.
         * @param operation The flooding proxy operation: set or clear.
         * @param tunnelZoneId The tunnel zone.
         * @param hostConfig The configuration of the host selected as flooding
         *                   proxy.
         */
        public FloodingProxyEvent(FloodingProxyOp operation, UUID tunnelZoneId,
                                  HostStateConfig hostConfig) {
            this.operation = operation;
            this.tunnelZoneId = tunnelZoneId;
            this.hostConfig = hostConfig;
        }
    }

    /**
     * Stores the configuration for a host to be used during the computation
     * of the flooding proxy.
     */
    public static class HostStateConfig {
        public final UUID id;
        public final IPv4Addr ipAddr;
        public final int floodingProxyWeight;

        public static final Comparator<HostStateConfig> comparator =
            new Comparator<HostStateConfig>() {
                @Override
                public int compare(HostStateConfig o1, HostStateConfig o2) {
                    return Integer.compare(o1.floodingProxyWeight,
                                           o2.floodingProxyWeight);
                }
            };

        public HostStateConfig(UUID id, IPv4Addr ipAddr,
                               int floodingProxyWeight) {
            this.id = id;
            this.ipAddr = ipAddr;
            this.floodingProxyWeight = floodingProxyWeight;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (null == obj || getClass() != obj.getClass())
                return false;

            HostStateConfig hostConfig = (HostStateConfig) obj;

            return Objects.equals(id, hostConfig.id) &&
                Objects.equals(ipAddr, hostConfig.ipAddr);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, ipAddr);
        }

        @Override
        public String toString() {
            return String.format(
                "VxLanHostConfig{ id=%s ipAddr=%s floodingProxyWeight=%s }",
                id, ipAddr, floodingProxyWeight);
        }
    }

    public final UUID id;

    private final DataClient midoClient;
    private final ZookeeperConnectionWatcher zkConnWatcher;

    private final HostStatePublisher hostMonitor;

    private final Subject<FloodingProxyEvent, FloodingProxyEvent>
        floodingProxyStream = ReplaySubject.createWithSize(1);

    private final List<Subscription> subscriptions = new ArrayList<>();
    private final ListMultimap<UUID, Subscription> hostSubscriptions =
        ArrayListMultimap.create();

    private final Map<UUID, HostState> members = new HashMap<>();

    private final Random random;

    private boolean disposed = false;

    private HostStateConfig floodingProxy = null;

    /**
     * Creates a new VXGW state for the specified tunnel zone identifier. The
     * state establishes a watcher on the tunnel zone membership.
     * @param id The tunnel zone identifier.
     * @param midoClient The data client.
     * @param zkConnWatcher The ZooKeeper connection watcher.
     *
     */
    public TunnelZoneState(@Nonnull UUID id,
                           @Nonnull DataClient midoClient,
                           @Nonnull ZookeeperConnectionWatcher zkConnWatcher,
                           @Nonnull HostStatePublisher hostMonitor,
                           @Nonnull Random random)
        throws StateAccessException {

        this.id = id;

        this.midoClient = midoClient;
        this.zkConnWatcher = zkConnWatcher;
        this.random = random;

        // Setup the host monitor.
        this.hostMonitor = hostMonitor;
        subscriptions.add(hostMonitor.getCreateObservable().subscribe(
            new Action1<HostState>() {
                @Override
                public void call(HostState hostState) {
                    onHostCreated(hostState);
                }
            }));
        subscriptions.add(hostMonitor.getDeleteObservable().subscribe(
            new Action1<HostState>() {
                @Override
                public void call(HostState hostState) {
                    onHostDeleted(hostState);
                }
            }));

        // Setup the membership monitor.
        EntityIdSetMonitor<UUID> membershipMonitor =
            midoClient.tunnelZonesGetMembershipsMonitor(id, zkConnWatcher);
        subscriptions.add(membershipMonitor.getObservable().subscribe(
            new Action1<EntityIdSetEvent<UUID>>() {
                @Override
                public void call(EntityIdSetEvent<UUID> event) {
                    if (disposed)
                        return;
                    switch (event.type) {
                        case CREATE:
                        case STATE:
                            onMemberAdded(event.value);
                            break;
                        case DELETE:
                            onMemberDeleted(event.value);
                            break;
                    }
                }
            }));
        membershipMonitor.notifyState();

        // Compute the flooding proxy.
        computeFloodingProxy(null);
    }

    /**
     * Cleans up the state of the current object before deletion by deleting
     * all subscriptions and blocking all notifications.
     */
    public void dispose() {
        // Prevent further notifications from ZooKeeper.
        disposed = true;
        // Clear the flooding proxy for this tunnel zone.
        floodingProxyStream.onNext(new FloodingProxyEvent(
            FloodingProxyOp.CLEAR, id, null));
        floodingProxyStream.onCompleted();
        // Remove all subscriptions.
        for (Subscription subscription : subscriptions) {
            subscription.unsubscribe();
        }
    }

    /**
     * Indicates whether this tunnel zone has a flooding proxy.
     * @return True if the tunnel zone has a flooding proxy, false otherwise.
     */
    public boolean hasFloodingProxy() {
        return floodingProxy != null;
    }

    /**
     * Gets the flooding proxy configuration for this tunnel zone.
     * @return The configuration of the host that has been selected as flooding
     *         proxy.
     */
    public HostStateConfig getFloodingProxy() {
        return floodingProxy;
    }

    /**
     * Gets an observable that issues notifications for changes of the current
     * flooding proxy. The notification is issued whenever the following
     * events occur, if and only if they change the current flooding proxy:
     * <ul>
     *     <li>A host that is a member of this tunnel zone is created.</li>
     *     <li>A host that is a member of this tunnel zone is deleted.</li>
     *     <li>A member of this tunnel zone is added.</li>
     *     <li>A member of this tunnel zone is deleted.</li>
     *     <li>The alive property of a member host changes.</li>
     *     <li>The flooding proxy weight of a member host changes.</li>
     * </ul>
     * @return The observable.
     */
    public Observable<FloodingProxyEvent> getFloodingProxyObservable() {
        return floodingProxyStream.asObservable();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (null == obj || getClass() != obj.getClass())
            return false;

        TunnelZoneState tunnelZone = (TunnelZoneState) obj;

        return Objects.equals(id, tunnelZone.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /**
     * Handles the creation of the host state by the VXLAN host monitor.
     * @param host The host state.
     */
    private void onHostCreated(HostState host) {
        // If this host is not a member, ignore the host.
        if (!members.containsKey(host.id))
            return;

        // Subscribe to the host alive property.
        hostSubscriptions.put(
            host.id,
            host.getIsAliveObservable().subscribe(
                new Action1<HostState>() {
                    @Override
                    public void call(HostState host) {
                        onHostIsAliveChanged(host);
                    }
                }));
        hostSubscriptions.put(
            host.id,
            host.getFloodingProxyWeightObservable().subscribe(
                new Action1<HostState>() {
                    @Override
                    public void call(HostState host) {
                        onHostFloodingProxyWeightChanged(host);
                    }
                }));

        // Update the flooding proxy.
        computeFloodingProxy(null);
    }

    /**
     * Handles the deletion of the host state by the VXLAN host monitor.
     * @param host The host state.
     */
    private void onHostDeleted(HostState host) {
        // If this host is not a member, ignore the host.
        if (!members.containsKey(host.id))
            return;

        // Unsubscribe all subscriptions to this host.
        for (Subscription subscription : hostSubscriptions.removeAll(host.id)) {
            subscription.unsubscribe();
        }

        // Update the flooding proxy.
        computeFloodingProxy(host);
    }

    /**
     * Handles the addition of a new member to the tunnel zone membership.
     * @param hostId The host identifier.
     */
    private void onMemberAdded(final UUID hostId) {
        log.debug("Tunnel zone {} member added: {}", id, hostId);

        // Get or try create a state for the host.
        HostState host = hostMonitor.getOrTryCreate(hostId);
        if (null == host)
            return;

        // Save the host state to the members list.
        members.put(hostId, host);

        // Subscribe to the host alive property.
        hostSubscriptions.put(
            hostId,
            host.getIsAliveObservable().subscribe(
                new Action1<HostState>() {
                    @Override
                    public void call(HostState host) {
                        onHostIsAliveChanged(host);
                    }
                }));
        hostSubscriptions.put(
            hostId,
            host.getFloodingProxyWeightObservable().subscribe(
                new Action1<HostState>() {
                    @Override
                    public void call(HostState host) {
                        onHostFloodingProxyWeightChanged(host);
                    }
                }));

        // Recompute the flooding proxy for this tunnel zone.
        computeFloodingProxy(null);
    }

    /**
     * Handles the deletion of a tunnel zone member.
     * @param hostId The host identifier.
     */
    private void onMemberDeleted(UUID hostId) {
        log.debug("Tunnel zone {} member deleted: {}", id, hostId);

        // Remove the member.
        HostState host = members.remove(hostId);

        // Unsubscribe all subscriptions to this host.
        for (Subscription subscription : hostSubscriptions.removeAll(hostId)) {
            subscription.unsubscribe();
        }

        // Update the flooding proxy.
        computeFloodingProxy(host);
    }

    /**
     * Handles changes to a host's is alive property.
     * @param host The host state that issued the notification.
     */
    private void onHostIsAliveChanged(HostState host) {
        // If this host is not a member, ignore the host.
        if (!members.containsKey(host.id))
            return;

        // Compute the flooding proxy weight for all tunnel zones where this
        // host is a member.
        log.debug("Host is alive changed: {}", host.id);

        // Update the flooding proxy.
        computeFloodingProxy(null);
    }

    /**
     * Handles changes to a host's flooding proxy weight property.
     * @param host The host state that issued the notification.
     */
    private void onHostFloodingProxyWeightChanged(HostState host) {
        // If this host is not a member, ignore the host.
        if (!members.containsKey(host.id))
            return;

        // Compute the flooding proxy weight for all tunnel zones where this
        // host is a member.
        log.debug("Host flooding proxy weight changed: {}", host.id);

        // Update the flooding proxy.
        computeFloodingProxy(null);
    }

    /**
     * Safe method for computing the flooding proxy for the current tunnel
     * zone. The method checks that the specified host is not selected as
     * a flooding proxy.
     * @param noHost The specified host cannot be selected as a flooding proxy.
     *               If null, all hosts are eligible for flooding proxy.
     */
    private void computeFloodingProxy(final HostState noHost) {
        try {
            computeFloodingProxyUnsafe(noHost);
        } catch (StateAccessException e) {
            log.warn("Failed configuring the flooding proxy for tunnel zone "
                     + "{}. Retrying.", id, e);
            zkConnWatcher.handleError(
                "Failed configuring the flooding proxy for tunnel zone " +
                id,
                new Runnable() {
                    @Override
                    public void run() {
                        computeFloodingProxy(noHost);
                    }
                }, e);
        }
    }

    /**
     * Unsafe method for computing the flooding proxy for the current tunnel
     * zone.
     * @param noHost The specified host cannot be selected as a flooding proxy.
     *               If null, all hosts are eligible for flooding proxy.
     */
    private void computeFloodingProxyUnsafe(HostState noHost)
        throws StateAccessException {
        log.debug("Computing flooding proxy for tunnel zone {} (ignoring host "
                  + "{})", id, noHost);

        int sumWeight = 0;
        List<HostStateConfig> candidateProxies = new ArrayList<>();

        HostStateConfig currentProxy = floodingProxy;
        HostStateConfig candidateProxy = null;

        boolean canKeepProxy = false;

        // Get the memberships for this tunnel zone.
        for (TunnelZone.HostConfig hostConfig :
            midoClient.tunnelZonesGetMemberships(id)) {

            // If the host does not have a tunnel zone address, skip.
            if (null == hostConfig.getIp())
                continue;

            if (null != noHost && hostConfig.getId().equals(noHost.id))
                continue;

            try {
                Host host = midoClient.hostsGet(hostConfig.getId());
                if (host == null) {
                    log.info("Host {} doesn't exist - found while computing " +
                             "flooding proxy for tunnel zone {}",
                             hostConfig.getId(), id);
                    continue;
                }
                if (!host.getIsAlive()) {
                    continue;
                }

                // TODO (alex): Should we check the host is in the members list?

                // Create a new flooding proxy configuration for this host.
                HostStateConfig proxyConfig = new HostStateConfig(
                    hostConfig.getId(), hostConfig.getIp(),
                    host.getFloodingProxyWeight());

                // Get the host flooding proxy weight.
                if (proxyConfig.floodingProxyWeight <= 0)
                    continue;

                candidateProxies.add(proxyConfig);
                sumWeight += proxyConfig.floodingProxyWeight;
                canKeepProxy |= proxyConfig.equals(currentProxy);

            } catch (StateAccessException e) {
                log.warn("Cannot retrieve state for host {}. Skipping host for "
                         + "tunnel zone {}",
                         new Object[] { hostConfig.getId(), id }, e);
            } catch (SerializationException e) {
                log.error("Cannot deserialize state for host {}. Skipping host "
                          + "for tunnel zone {}",
                          new Object[] { hostConfig.getId(), id}, e);
            }
        }

        log.debug("Flooding proxy candidate hosts: {}", candidateProxies);

        // If the list of candidate proxies is empty.
        if (candidateProxies.isEmpty()) {
            log.warn("There is no suitable flooding proxy for tunnel zone {}",
                     id);
            // Set the current flooding proxy configuration to null.
            floodingProxy = null;
            // Notify the removal of the flooding proxy.
            floodingProxyStream.onNext(new FloodingProxyEvent(
                FloodingProxyOp.CLEAR, id, null));
            return;
        }

        // Sort the candidate proxies: provides a deterministic behavior for
        // unit testing (not necessary in practice).

        Collections.sort(candidateProxies, HostStateConfig.comparator);

        // Else, choose a random proxy from all candidate proxies, weighted by
        // their flooding proxy weight.

        int randomWeight = random.nextInt(sumWeight);
        for (int index = 0, sum = 0;
             index < candidateProxies.size() && sum <= randomWeight;
             index++) {
            candidateProxy = candidateProxies.get(index);
            sum += candidateProxy.floodingProxyWeight;
        }

        if (null == candidateProxy) {
            log.error("Failed to select a flooding proxy host for tunnel zone "
                      + "{}", id);
            return;
        }

        // Do not change the proxy if the new proxy is the same, or if the new
        // proxy has the same weight as the old proxy.
        if (null != currentProxy && canKeepProxy &&
            (candidateProxy.equals(currentProxy) ||
             candidateProxy.floodingProxyWeight ==
             currentProxy.floodingProxyWeight)) {

            log.debug("Keeping same flooding proxy for tunnel zone {}: {}",
                      id, currentProxy);

        } else {

            log.info("Configuring host {} with weight {} as flooding proxy for "
                     + "tunnel zone {}",
                     candidateProxy.id, candidateProxy.floodingProxyWeight, id);

            // Notify the set of the flooding proxy.
            floodingProxy = candidateProxy;
            floodingProxyStream.onNext(new FloodingProxyEvent(
                FloodingProxyOp.SET, id, floodingProxy));
        }
    }
}
