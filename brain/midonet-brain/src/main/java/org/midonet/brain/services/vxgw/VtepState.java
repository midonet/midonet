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

package org.midonet.brain.services.vxgw;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.packets.IPv4Addr;

/**
 * Maintains local state information for an existing VTEP.
 */
public class VtepState {

    private static final Logger log =
        LoggerFactory.getLogger(VtepState.class);

    public final IPv4Addr vtepIp;
    public final UUID ownerId;

    private final DataClient midoClient;
    private final ZookeeperConnectionWatcher zkConnWatcher;

    private final Subject<VtepState, VtepState> streamOwner
        = PublishSubject.create();

    private class OwnerWatcher implements Watcher, Runnable {
        @Override
        public void run() {
            try {
                // Try take ownership, while installing a watch on the owner
                // node.
                UUID owner = midoClient.tryOwnVtep(vtepIp, ownerId, this);
                if (owner.equals(ownerId) && !owned) {
                    // Notify the acquired ownership.
                    log.info("VXGW service {} took ownership of VTEP {}",
                             ownerId, vtepIp);

                    owned = true;
                    streamOwner.onNext(VtepState.this);
                }
                if (!owner.equals(ownerId) && owned) {
                    // Notify the lost ownership.
                    log.warn("VXGW service {} lost ownership of VTEP {} to {}",
                             ownerId, vtepIp, owner);
                    owned = false;
                    streamOwner.onNext(VtepState.this);
                }
            } catch (StateAccessException e) {
                zkConnWatcher.handleError("OwnerWatcher" + vtepIp, this, e);
            } catch (SerializationException e) {
                log.error("The current owner of VTEP {} has an invalid "
                          + "identifier and VTEP is ignored.", vtepIp);
            }
        }

        @Override
        public void process(WatchedEvent event) {
            if (!disposed) {
                run();
            }
        }
    }

    private boolean disposed = false;
    private boolean owned = false;

    /**
     * Creates a new VTEP state for the specified VTEP IP address. The state
     * establishes watchers on the VTEP owner property to detect changes in the
     * VTEP ownership.
     *
     * @param vtepIp The VTEP IP address.
     * @param ownerId The VXGW service identifier.
     * @param midoClient The data client.
     * @param zkConnWatcher The ZooKeeper connection watcher.
     */
    public VtepState(@Nonnull IPv4Addr vtepIp, @Nonnull UUID ownerId,
                     @Nonnull DataClient midoClient,
                     @Nonnull ZookeeperConnectionWatcher zkConnWatcher)
        throws StateAccessException, SerializationException {

        this.vtepIp = vtepIp;
        this.ownerId = ownerId;

        this.midoClient = midoClient;
        this.zkConnWatcher = zkConnWatcher;

        // Try take ownership and set watcher.
        if (midoClient.tryOwnVtep(vtepIp, ownerId, new OwnerWatcher())
            .equals(ownerId)) {
            owned = true;
        }
    }

    /**
     * Disposes the current object by removing the current node as owner, and
     * blocking all notifications. After calling this method, the VXGW service
     * is no longer the owner of the VTEP and it should not perform any other
     * write operation.
     */
    public void dispose() {
        // Prevent setting further watches in ZooKeeper.
        disposed = true;
        // Remove VTEP ownership.
        if (owned) try {
            log.info("VXGW service {} releasing ownership of VTEP {}",
                     new Object[] { ownerId, vtepIp });

            owned = false;
            // First, notify the VTEP release.
            streamOwner.onNext(VtepState.this);
            // Then, remove the ownership node.
            midoClient.deleteVtepOwner(vtepIp, ownerId);
        } catch (StateAccessException | SerializationException e) {
            log.warn("Deleting VTEP {} ownership upon disposal failed",
                     vtepIp, e);
        }
        // Complete the notification streams.
        streamOwner.onCompleted();
    }

    /**
     * Returns an observable that notifies the changes of the VTEP owner status.
     */
    public Observable<VtepState> getOwnerObservable() {
        return streamOwner.asObservable();
    }

    /**
     * Returns whether the VTEP is owned by the current service.
     */
    public boolean isOwned() {
        return owned;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (null == obj || getClass() != obj.getClass())
            return false;

        VtepState vtep = (VtepState) obj;

        return Objects.equals(vtepIp, vtep.vtepIp) &&
            Objects.equals(ownerId, vtep.ownerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vtepIp, ownerId);
    }

    @Override
    public String toString() {
        return vtepIp.toString();
    }
}
