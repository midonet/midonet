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
package org.midonet.management;

import java.beans.ConstructorProperties;
import com.google.common.primitives.Longs;

public class FlowStats {
    /** Number of matched packets. */
    /*__u64*/ public long packets;

    /** Number of matched bytes. */
    /*__u64*/ public long bytes;

    public FlowStats() {
        packets = 0;
        bytes = 0;
    }

    @ConstructorProperties({"packets", "bytes"})
    public FlowStats(long packets, long bytes) {
        this.packets = packets;
        this.bytes = bytes;
    }

    public void add(long deltaPackets, long deltaBytes) {
        packets += deltaPackets;
        bytes += deltaBytes;
    }

    public void add(FlowStats delta) {
        add(delta.packets, delta.bytes);
    }

    public long getPackets() {
        return packets;
    }

    public long getBytes() {
        return bytes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowStats that = (FlowStats) o;

        return this.bytes == that.bytes
            && this.packets == that.packets;
    }

    @Override
    public int hashCode() {
        return 31 * Longs.hashCode(packets) + Longs.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "FlowStats{" +
            "packets=" + packets +
            ", bytes=" + bytes +
            '}';
    }

    public void clear() {
        packets = 0;
        bytes = 0;
    }
}
