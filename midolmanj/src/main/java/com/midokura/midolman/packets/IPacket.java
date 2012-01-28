package com.midokura.midolman.packets;

import java.nio.ByteBuffer;

/**
*
* @author David Erickson (daviderickson@cs.stanford.edu)
*/
public interface IPacket {
    /**
     * 
     * @return
     */
    public IPacket getPayload();

    /**
     * 
     * @param packet
     * @return
     */
    public IPacket setPayload(IPacket packet);

    /**
     * 
     * @return
     */
    public IPacket getParent();

    /**
     * 
     * @param packet
     * @return
     */
    public IPacket setParent(IPacket packet);

    /**
     * Sets all payloads parent packet if applicable, then serializes this 
     * packet and all payloads
     * @return a byte[] containing this packet and payloads
     */
    public byte[] serialize();

    /**
     * Deserializes this packet layer and all possible payloads
     * @param bb  ByteBuffer of the data to deserialize.
     * @return the deserialized data
     */
    public IPacket deserialize(ByteBuffer bb) throws MalformedPacketException;
}
