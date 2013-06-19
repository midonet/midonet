/*
 * Copyright 2011 Midokura KK
 */
package org.midonet.packets;

public interface Transport {

    int MAX_PORT_NO = 65535;

    int getSourcePort();
    int getDestinationPort();
    void setSourcePort(int port);
    void setDestinationPort(int port);

    IPacket getPayload();
}
