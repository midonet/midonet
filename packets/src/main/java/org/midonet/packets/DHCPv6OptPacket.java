/*
 * Copyright 2011 Midokura KK
 */
package org.midonet.packets;

import java.nio.ByteBuffer;

/*
 * more specific version of the IPacket interface.
 * Adds functions specific to DHCPv6 options.
 */
public interface DHCPv6OptPacket extends IPacket {

    /*
     * set the DHCPv6 option type.
     */
    public void setCode(short code);

    /*
     * get the DHCPv6 option type.
     */
    public short getCode();

    /*
     * get the DHCPv6 option length.
     */
    public void setLength(short length);

    /*
     * get the DHCPv6 option length.
     */
    public short getLength();

    /*
     * Deserialize just the payload of the option. Everything except for
     * the code and length (option header).
     */
    public void deserializeData(ByteBuffer bb);
}
