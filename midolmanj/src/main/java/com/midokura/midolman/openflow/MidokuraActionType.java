/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.openflow;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

import org.openflow.protocol.action.OFAction;

public enum MidokuraActionType {

    CHECK_TCP_FLAGS(0, MidokuraCheckTcpFlags.class),
    CHECK_ACK_SEQ_NUM(1, MidokuraCheckTcpAckSeqNum.class);

    protected static Map<Short, MidokuraActionType> mapping;

    protected Class<? extends MidokuraAction> clazz;
    protected Constructor<? extends MidokuraAction> constructor;
    protected int minLen;
    protected short type;

    /**
     * Store some information about the OpenFlow Action type, including wire
     * protocol type number, length, and derrived class
     *
     * @param type Wire protocol number associated with this OFType
     * @param requestClass The Java class corresponding to this type of OpenFlow Action
     */
    MidokuraActionType(int type, Class<? extends MidokuraAction> clazz) {
        this.type = (short) type;
        this.clazz = clazz;
        try {
            this.constructor = clazz.getConstructor(new Class[]{});
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failure getting constructor for class: " + clazz, e);
        }
        MidokuraActionType.addMapping(this.type, this);
    }

    /**
     * Adds a mapping from type value to MidokuraActionType enum
     *
     * @param i OpenFlow wire protocol Action type value
     * @param t type
     */
    static public void addMapping(short i, MidokuraActionType t) {
        if (mapping == null)
            mapping = new HashMap<Short, MidokuraActionType>();

        MidokuraActionType.mapping.put(i, t);
    }

    /**
     * Given a wire protocol OpenFlow type number, return the OFType associated
     * with it
     *
     * @param i wire protocol number
     * @return OFType enum type
     */

    static public MidokuraActionType valueOf(short i) {
        return MidokuraActionType.mapping.get(i);
    }

    /**
     * @return Returns the wire protocol value corresponding to this
     *         MidokuraActionType
     */
    public short getTypeValue() {
        return this.type;
    }

    /**
     * @return return the OFAction subclass corresponding to this MidokuraActionType
     */
    public Class<? extends OFAction> toClass() {
        return clazz;
    }

    /**
     * Returns the no-argument Constructor of the implementation class for
     * this MidokuraActionType
     * @return the constructor
     */
    public Constructor<? extends OFAction> getConstructor() {
        return constructor;
    }
}
