/*
 * Copyright 2011 Midokura KK
 */
package org.midonet.packets;

public interface Transport {

    int getSourcePort();
    int getDestinationPort();
    void setSourcePort(int port);
    void setDestinationPort(int port);

    IPacket getPayload();
}
