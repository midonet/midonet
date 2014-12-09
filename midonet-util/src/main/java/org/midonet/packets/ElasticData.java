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

/**
 * ElasticData is a packet payload data structure, which can be shrunken when
 * it is serialized.
 */
public class ElasticData extends Data {
    private int currentLength = 0;

    public ElasticData() {
        super();
    }

    public ElasticData(byte[] data) {
        super(data);
        this.currentLength = data.length;
    }

    public ElasticData(byte[] data , int length) {
        super(data);
        this.currentLength = length;
    }

    public int getLength() {
        return this.currentLength;
    }

    public void setLength(int length) {
        this.currentLength = length;
    }

    public int serialize(ByteBuffer bb) {
        int length = getLength();
        bb.put(data, 0, length);
        return length;
    }
}
