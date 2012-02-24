/*
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.util.HashMap;
import java.util.Map;

/**
 * List of OpenFlow Action types and mappings to wire protocol value and
 * derived classes
 */
public enum NxActionType {
    NXAST_SET_TUNNEL          (2, NxActionSetTunnelKey32.class),
    NXAST_SET_TUNNEL64        (9, NxActionSetTunnelKey64.class);

    protected static Map<Short, NxActionType> mapping;

    protected Class<? extends NxAction> clazz;
    protected short type;

    /**
     * Store some information about the NxAction type, including wire
     * protocol type number and derived class
     *
     * @param type Wire protocol number associated with this NxActionType
     * @param clazz The Java class corresponding to this type of NxAction
     */
    NxActionType(int type, Class<? extends NxAction> clazz) {
        this.type = (short) type;
        this.clazz = clazz;
        NxActionType.addMapping(this.type, this);
    }

    /**
     * Adds a mapping from type value to NxActionType enum
     *
     * @param i NXM wire protocol Action type value
     * @param t type
     */
    static public void addMapping(short i, NxActionType t) {
        if (mapping == null)
            mapping = new HashMap<Short, NxActionType>();

        NxActionType.mapping.put(i, t);
    }

    /**
     * Given a wire protocol NxAction type number, return the NxAction type
     * associated with it.
     *
     * @param i wire protocol number
     * @return NxActionType enum type
     */

    static public NxActionType valueOf(short i) {
        return NxActionType.mapping.get(i);
    }

    /**
     * @return Returns the wire protocol value corresponding to this
     *         NxActionType
     */
    public short getTypeValue() {
        return this.type;
    }

    /**
     * @return return the NxAction subclass corresponding to this NxActionType
     */
    public Class<? extends NxAction> toClass() {
        return clazz;
    }

}
