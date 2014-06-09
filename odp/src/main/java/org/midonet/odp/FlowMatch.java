/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.flows.FlowKey;
import org.midonet.odp.flows.FlowKeyICMPEcho;
import org.midonet.odp.flows.FlowKeys;

/**
 * An ovs datapath flow match object. Contains an ordered list of
 * FlowKey&lt;?&gt; instances.
 *
 * @see FlowKey
 * @see org.midonet.odp.flows.FlowKeys
 */
public class FlowMatch {

    private boolean userSpaceOnly = false;
    private List<FlowKey> keys = new ArrayList<>();

    public FlowMatch() {
        keys = null;
    }

    /**
     * BEWARE: this method does a direct assign of keys to the private
     * collection.
     *
     * @param keys
     */
    public FlowMatch(@Nonnull List<FlowKey> keys) {
        this.setKeys(keys);
    }

    public FlowMatch addKey(FlowKey key) {
        if (keys == null) {
            keys = new ArrayList<>();
        }
        keys.add(FlowKeys.intern(key));
        userSpaceOnly |= (key instanceof FlowKey.UserSpaceOnly);
        return this;
    }

    @Nonnull
    public List<FlowKey> getKeys() {
        return keys;
    }

    public FlowMatch setKeys(@Nonnull List<FlowKey> keys) {
        this.userSpaceOnly = false;
        this.keys = keys.isEmpty() ? keys : new ArrayList<FlowKey>(keys.size());
        for (FlowKey key : keys) {
            userSpaceOnly |= (key instanceof FlowKey.UserSpaceOnly);
            this.keys.add(FlowKeys.intern(key));
        }
        return this;
    }

    public FlowMatch addKeys(@Nonnull List<FlowKey> keys) {
        this.userSpaceOnly = false;
        for (FlowKey key: keys) {
            userSpaceOnly |= (key instanceof FlowKey.UserSpaceOnly);
            this.keys.add(FlowKeys.intern(key));
        }
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowMatch flowMatch = (FlowMatch) o;

        if (keys == null ? flowMatch.keys != null
                         : !keys.equals(flowMatch.keys))
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        return keys != null ? keys.hashCode() : 0;
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

    public static FlowMatch buildFrom(ByteBuffer buf) {
        return new FlowMatch(FlowKeys.buildFrom(buf));
    }

    public void replaceKey(int index, FlowKey flowKey) {
        keys.set(index, flowKey);
    }
}
