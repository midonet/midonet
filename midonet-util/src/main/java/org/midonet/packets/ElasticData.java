/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.packets;

import java.util.Arrays;

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

    public byte[] serialize() {
        return Arrays.copyOfRange(this.data, 0, currentLength);
    }
}

