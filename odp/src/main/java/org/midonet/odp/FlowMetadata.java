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

package org.midonet.odp;

import java.nio.ByteBuffer;

import org.midonet.netlink.AttributeHandler;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.flows.FlowStats;

/**
 * The flow metadata as reported by OpenVSwitch
 */
public class FlowMetadata implements AttributeHandler {
    private FlowStats stats;
    private byte tcpFlags;

    // this field is both used by the ovs kernel and by midolman packet pipeline
    // to track time statistics. Because it may be incoherent for the same flow
    // between ovs and midolman , it is ignored in equals() and hashCode().
    private long lastUsedMillis;

    public FlowMetadata() {
        stats = new FlowStats();
    }

    public FlowMetadata(FlowStats stats) {
        this.stats = stats;
    }

    public FlowStats getStats() {
        return stats;
    }

    public Byte getTcpFlags() {
        return tcpFlags;
    }

    public long getLastUsedMillis() {
        return lastUsedMillis;
    }

    public void setLastUsedMillis(long lastUsedMillis) {
        this.lastUsedMillis = lastUsedMillis;
    }

    public void deserialize(ByteBuffer buf) {
        buf.getInt(); // read datapath index;
        NetlinkMessage.scanAttributes(buf, this);
    }

    public void clear() {
        stats.clear();
        tcpFlags = 0;
        lastUsedMillis = 0;
    }

    @Override
    public void use(ByteBuffer buffer, short id) {
        switch(NetlinkMessage.unnest(id)) {
            case OpenVSwitch.Flow.Attr.Stats:
                stats.deserialize(buffer);
                break;

            case OpenVSwitch.Flow.Attr.TCPFlags:
                tcpFlags = buffer.get();
                break;

          case OpenVSwitch.Flow.Attr.Used:
                lastUsedMillis = buffer.getLong();
                break;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        FlowMetadata that = (FlowMetadata) o;

        if (tcpFlags != that.tcpFlags) {
            return false;
        }
        if (!stats.equals(that.stats)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = stats.hashCode();
        result = 31 * result + (int) tcpFlags;
        return result;
    }

    @Override
    public String toString() {
        return "FlowMetadata{" +
            ", stats=" + stats +
            ", tcpFlags=" + tcpFlags +
            ", lastUsedMillis=" + lastUsedMillis +
            "}";
    }
}