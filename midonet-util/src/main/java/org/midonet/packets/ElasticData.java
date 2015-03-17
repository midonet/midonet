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

package org.midonet.packets;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * ElasticData is a packet payload data structure, whose size can be limited.
 */
public class ElasticData extends Data {
    private int limit = 0;

    public ElasticData() {
    }

    public ElasticData(byte[] data) {
        this(data, data.length);
    }

    public ElasticData(byte[] data , int limit) {
        super(data);
        limit(limit);
    }

    public int capacity() {
        return data.length;
    }

    public int limit() {
        return limit;
    }

    public void limit(int limit) {
        if (limit > data.length)
            throw new IllegalArgumentException("Can't set a bigger length than the capacity");
        this.limit = limit;
    }

    public int serialize(ByteBuffer bb) {
        bb.put(data, 0, limit);
        return limit;
    }

    @Override
    public byte[] serialize() {
        return Arrays.copyOf(data, limit);
    }

    @Override
    public Data setData(byte[] data) {
        throw new IllegalStateException("Cannot set data on elastic data");
    }

    @Override
    public IPacket deserialize(ByteBuffer bb) {
        limit = bb.remaining();
        return super.deserialize(bb);
    }
}
