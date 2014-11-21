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
package org.midonet.odp;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

import org.midonet.netlink.AttributeHandler;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.Reader;
import org.midonet.odp.flows.FlowKey;
import org.midonet.odp.flows.FlowKeys;

/**
 * An ovs datapath flow match object. Contains a list of FlowKey instances.
 *
 * @see FlowKey
 * @see org.midonet.odp.flows.FlowKeys
 */
public class FlowMatch implements AttributeHandler {

    private boolean userSpaceOnly = false;
    private final List<FlowKey> keys = new ArrayList<>();
    private int keysHashCode = 0;
    private int connectionHash = 0;
    private long sequence;

    public FlowMatch() { }

    public FlowMatch(@Nonnull Iterable<FlowKey> keys) {
        this.addKeys(keys);
    }

    public FlowMatch addKey(FlowKey key) {
        userSpaceOnly |= (key instanceof FlowKey.UserSpaceOnly);
        keys.add(FlowKeys.intern(key));
        invalidateHashCode();
        return this;
    }

    @Nonnull
    public List<FlowKey> getKeys() {
        return keys;
    }

    public FlowMatch addKeys(@Nonnull Iterable<FlowKey> keys) {
        for (FlowKey key : keys) {
            addKey(key);
        }
        return this;
    }

    public boolean hasKey(int keyId) {
        for (FlowKey key : keys) {
            if (key.attrId() == keyId)
                return true;
        }
        return false;
    }

    public long getSequence() {
        return sequence;
    }

    public FlowMatch setSequence(long sequence) {
        this.sequence = sequence;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowMatch that = (FlowMatch) o;

        return this.keys.equals(that.keys);
    }

    public int connectionHash() {
        if (connectionHash == 0) {
            int connectionKeys = 0; // should be two
            int connHash = 0;
            for (int i = 0; i < keys.size(); i++) {
                int keyHash = keys.get(i).connectionHash();
                if (keyHash != 0) {
                    connectionKeys++;
                    connHash = 31 * connHash + keyHash;
                }
            }
            if (connectionKeys == 2)
                this.connectionHash = connHash;
            else
                this.connectionHash = hashCode();
        }

        return this.connectionHash;
    }

    @Override
    public int hashCode() {
        if (keysHashCode == 0) {
            keysHashCode = keys.hashCode();
        }
        return keysHashCode;
    }

    private void invalidateHashCode() {
        keysHashCode = 0;
        connectionHash = 0;
    }

    @Override
    public String toString() {
        return "FlowMatch{ keys=" + keys +
               ", UserSpaceOnly=" + userSpaceOnly + "}";
    }

    /**
     * Tells if all the FlowKeys contained in this match are compatible with
     * Netlink.
     *
     * @return
     */
    public boolean isUserSpaceOnly() {
        return userSpaceOnly;
    }

    /**
     * We need to provide a setter because FlowMatches may need to alter the
     * list.
     *
     * TODO (galo) - I'm doing it this way to avoid rebuilding the entire list
     * which wouldn't be horrible because it will get done once at the worst,
     * and it would be much nicer.
     *
     * @param isUserSpaceOnly
     */
    public void setUserSpaceOnly(boolean isUserSpaceOnly) {
       userSpaceOnly = isUserSpaceOnly;
    }

    public void replaceKey(int index, FlowKey flowKey) {
        keys.set(index, flowKey);
        invalidateHashCode();
    }

    public void use(ByteBuffer buf, short id) {
        FlowKey key = FlowKeys.newBlankInstance(id);
        if (key == null)
            return;
        key.deserializeFrom(buf);
        addKey(key);
    }

    public static Reader<FlowMatch> reader = new Reader<FlowMatch>() {
        public FlowMatch deserializeFrom(ByteBuffer buf) {
            FlowMatch fm = new FlowMatch();
            NetlinkMessage.scanAttributes(buf, fm);
            return fm;
        }
    };
}
