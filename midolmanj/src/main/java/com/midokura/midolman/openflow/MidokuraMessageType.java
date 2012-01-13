/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.openflow;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

public enum MidokuraMessageType {
    MIDO_HELLO(0, MidokuraHello.class);

    static Map<Byte, MidokuraMessageType> mapping;

    protected Class<? extends MidokuraMessage> clazz;
    protected Constructor<? extends MidokuraMessage> constructor;
    protected byte type;

    /**
     * Store some information about the OpenFlow type, including wire protocol
     * type number, length, and derived class
     *
     * @param type Wire protocol number associated with this Midokura message type
     * @param requestClass The Java class corresponding to this type of OpenFlow
     *              message
     */
    MidokuraMessageType(int type, Class<? extends MidokuraMessage> clazz) {
        this.type = (byte) type;
        this.clazz = clazz;
        try {
            this.constructor = clazz.getConstructor(new Class[]{});
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failure getting constructor for class: " + clazz, e);
        }
        MidokuraMessageType.addMapping(this.type, this);
    }

    /**
     * Adds a mapping from type value to OFType enum
     *
     * @param i OpenFlow wire protocol type
     * @param t type
     */
    static public void addMapping(byte i, MidokuraMessageType t) {
        if (mapping == null)
            mapping = new HashMap<Byte, MidokuraMessageType>();
        MidokuraMessageType.mapping.put(i, t);
    }

    /**
     * Remove a mapping from type value to OFType enum
     *
     * @param i OpenFlow wire protocol type
     */
    static public void removeMapping(byte i) {
        mapping.remove(i);
    }

    /**
     * Given a wire protocol OpenFlow type number, return the OFType associated
     * with it
     *
     * @param i wire protocol number
     * @return the message type
     */

    static public MidokuraMessageType valueOf(Byte i) {
        return MidokuraMessageType.mapping.get(i);
    }

    /**
     * @return Returns the wire protocol value corresponding to this OFType
     */
    public byte getTypeValue() {
        return this.type;
    }

    /**
     * @return return the OFMessage subclass corresponding to this OFType
     */
    public Class<? extends MidokuraMessage> toClass() {
        return clazz;
    }

    /**
     * Returns the no-argument Constructor of the implementation class for
     * this message type
     * @return the constructor
     */
    public Constructor<? extends MidokuraMessage> getConstructor() {
        return constructor;
    }

}
