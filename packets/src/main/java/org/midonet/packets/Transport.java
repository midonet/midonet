/*
 * Copyright 2011 Midokura KK
 */
package org.midonet.packets;

public interface Transport {

    int MIN_PORT_NO = 0;
    int MAX_PORT_NO = 65535;

    int getSourcePort();
    int getDestinationPort();
    void setSourcePort(int port);
    void setDestinationPort(int port);

    IPacket getPayload();
}
