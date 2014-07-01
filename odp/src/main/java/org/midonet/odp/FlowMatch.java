/*
* Copyright 2012 Midokura Europe SARL
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowMatch that = (FlowMatch) o;

        return this.keys.equals(that.keys);
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
