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

import com.google.common.primitives.Longs;

public class FlowStats {

    /** Number of matched packets. */
    /*__u64*/ private final long n_packets;

    /** Number of matched bytes. */
    /*__u64*/ private final long n_bytes;

    public FlowStats(long numPackets, long numBytes) {
        n_packets = numPackets;
        n_bytes = numBytes;
    }

    public long getNoPackets() {
        return n_packets;
    }

    public long getNoBytes() {
        return n_packets;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowStats that = (FlowStats) o;

        return (this.n_bytes == that.n_bytes)
            && (this.n_packets == that.n_packets);
    }

    @Override
    public int hashCode() {
        return 31 * Longs.hashCode(n_packets) + Longs.hashCode(n_bytes);
    }

    @Override
    public String toString() {
        return "FlowStats{" +
            "n_packets=" + n_packets +
            ", n_bytes=" + n_bytes +
            '}';
    }

    public static FlowStats buildFrom(ByteBuffer buf) {
        long n_packets = buf.getLong();
        long n_bytes = buf.getLong();
        return new FlowStats(n_packets, n_bytes);
    }
}
