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

    /** Returns a hash code which only uses for its calculation fields that are
     *  part of a stateful L4 connection. This allows for  a consistent result
     *  across multiple matches that belong to the same connection.
     */
    int connectionHash();

    /**
     * Should be used by those keys that are only supported in user space.
     *
     * Note that Matches containing any UserSpaceOnly key will NOT be sent
     * to the datapath, and will also need to have all UserSpace-related actions
     * applied before being sent to the DP.
     */
    interface UserSpaceOnly { }
}
