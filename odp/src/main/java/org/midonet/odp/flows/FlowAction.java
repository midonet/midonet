/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

public interface FlowAction {

    /** write the action into a bytebuffer, without its header. */
    int serializeInto(ByteBuffer buf);

    /** give the netlink attr id of this action instance. */
    short attrId();

    /** populate the internal state of this action instance from the content of
     *  the given ByteBuffer. Used in conjunction with the scanAttributes
     *  iterator of NetlinkMessage when reconstructing a flow action list. */
    void deserializeFrom(ByteBuffer buf);
}
