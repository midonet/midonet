/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

public interface FlowKey {

    /** write the key into a bytebuffer, without its header. */
    int serializeInto(ByteBuffer buf);

    /** give the netlink attr id of this key instance. */
    short attrId();

    /** populate the internal state of this key instance from the content of
     *  the given ByteBuffer. Used in conjunction with the scanAttributes
     *  iterator of NetlinkMessage when reconstructing a flow match. */
    void deserializeFrom(ByteBuffer buf);

    /**
     * Should be used by those keys that are only supported in user space.
     *
     * Note that Matches containing any UserSpaceOnly key will NOT be sent
     * to the datapath, and will also need to have all UserSpace-related actions
     * applied before being sent to the DP.
     */
    interface UserSpaceOnly { }
}
