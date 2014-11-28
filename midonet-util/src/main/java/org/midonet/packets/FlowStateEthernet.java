/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.packets;

/**
 * FlowStateEthernet is an ethernet frame contains a flow state UDP packet,
 * which length is flexible.
 */
public class FlowStateEthernet extends Ethernet {
    public void setElasticDataLength(int length) {
        IPv4 ipv4 = (IPv4) getPayload();
        UDP udp = (UDP) ipv4.getPayload();
        ElasticData elasticData = (ElasticData) udp.getPayload();
        elasticData.setLength(length);
    }
}

