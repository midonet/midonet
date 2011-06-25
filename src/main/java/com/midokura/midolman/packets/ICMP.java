/*
 * Copyright 2011 Midokura KK 
 */
package com.midokura.midolman.packets;

import java.nio.ByteBuffer;

public class ICMP extends BasePacket {
    public static final byte PROTOCOL_NUMBER = 1;

    char type;
    char code;
    short checksum;
    int quench;
    
    @Override
    public byte[] serialize() {
        ByteBuffer bb = ByteBuffer.allocate(8);
        
        bb.putChar(type);
        bb.putChar(code);
        //TODO: calculate checksum
        bb.putShort(checksum);
        bb.putInt(quench);
        
        return bb.array();
    }

    @Override
    public IPacket deserialize(byte[] data, int offset, int length) {
        ByteBuffer bb = ByteBuffer.wrap(data, offset, length);
        type = bb.getChar();
        code = bb.getChar();
        checksum = bb.getShort();
        
        return this;
    }

}
