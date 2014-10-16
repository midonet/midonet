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
 * An OVS flow mask object, used in megaflows.
 *
 * A flow mask is a list of keys where values are interpreted as masks
 * where each 1-bit requires that the corresponding bit in the key must
 * match. Each 0-bit in mask causes the corresponding bit to be ignored.
 *
 * There is a dependency relation between masks, as some of them can not be
 * matched unless lower level matches are **exact**. For example, TCP ports or
 * flags cannot be matched unless we require a exact match for IP
 * protocol == TCP. In the same way, IP matches cannot be done unless we require
 * an exact match for Ethernet protocol == IP.
 *
 * From OVS 2.0 docs:
 * The behavior when using overlapping wildcarded flows is undefined. It is the
 * responsibility of the user space program to ensure that any incoming packet
 * can match at most one flow, wildcarded or not. The current implementation
 * performs best-effort detection of overlapping wildcarded flows and may reject
 * some but not all of them. However, this behavior may change in future versions.
 *
 * @see FlowKey
 * @see org.midonet.odp.flows.FlowKeys
 */
public class FlowMask implements AttributeHandler {

    private final List<FlowKey> keys = new ArrayList<>();
    private int keysHashCode = 0;

    // Note: not all keys are maskeable. This is the list of keys that current
    // openVSwitch supports:
    //
    // OVS_KEY_ATTR_TUNNEL
    // OVS_KEY_ATTR_IN_PORT
    // OVS_KEY_ATTR_ETHERTYPE
    // OVS_KEY_ATTR_IPV4
    // OVS_KEY_ATTR_IPV6
    // OVS_KEY_ATTR_TCP
    // OVS_KEY_ATTR_TCP_FLAGS
    // OVS_KEY_ATTR_UDP
    // OVS_KEY_ATTR_SCTP
    // OVS_KEY_ATTR_ICMP
    // OVS_KEY_ATTR_ICMPV6
    // OVS_KEY_ATTR_ARP
    // OVS_KEY_ATTR_ND
    //

    final public static byte   BYTE_ANY        = (byte) 0x00;
    final public static byte   BYTE_EXACT      = (byte) 0xFF;
    final public static int    PRIO_ANY        = 0x00000000;
    final public static int    PRIO_EXACT      = 0xFFFFFFFF;
    final public static int    INPORT_ANY      = 0x00000000;
    final public static int    INPORT_EXACT    = 0xFFFFFFFF;
    final public static byte[] ETHER_ANY       = new byte[] { BYTE_ANY, BYTE_ANY,
                                                              BYTE_ANY, BYTE_ANY,
                                                              BYTE_ANY, BYTE_ANY };
    final public static byte[] ETHER_EXACT     = new byte[] { BYTE_EXACT, BYTE_EXACT,
                                                              BYTE_EXACT, BYTE_EXACT,
                                                              BYTE_EXACT, BYTE_EXACT };
    final public static short  ETHERTYPE_ANY   = (short) 0x0000;
    final public static short  ETHERTYPE_EXACT = (short) 0xFFFF;
    final public static int    IP_ANY          = 0x00000000;
    final public static int    IP_EXACT        = 0xFFFFFFFF;
    final public static int    TCP_ANY         = 0x0000;
    final public static int    TCP_EXACT       = 0xFFFF;
    final public static short  TCPFLAGS_ANY    = (short) 0x0000;
    final public static short  TCPFLAGS_EXACT  = (short) 0xFFFF;

    public FlowMask() { }

    public FlowMask(@Nonnull Iterable<FlowKey> keys) {
        this.addKeys(keys);
    }

    public FlowMask addKey(FlowKey key) {
        keys.add(FlowKeys.intern(key));
        invalidateHashCode();
        return this;
    }

    @Nonnull
    public List<FlowKey> getKeys() {
        return keys;
    }

    public FlowMask addKeys(@Nonnull Iterable<FlowKey> keys) {
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
        FlowMask that = (FlowMask) o;

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
        return "FlowMask{ keys=" + keys + "}";
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

    public static Reader<FlowMask> reader = new Reader<FlowMask>() {
        public FlowMask deserializeFrom(ByteBuffer buf) {
            FlowMask fm = new FlowMask();
            NetlinkMessage.scanAttributes(buf, fm);
            return fm;
        }
    };
}
