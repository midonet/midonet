/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.openflow.nxm;


import java.util.HashMap;
import java.util.Map;

import org.openflow.protocol.OFMessage;

public enum NxType {
    NXT_SET_FLOW_FORMAT         (12, NxSetFlowFormat.class),
    NXT_FLOW_MOD                (13, NxFlowMod.class),
    NXT_FLOW_REMOVED            (14, NxFlowRemoved.class),
    NXT_SET_PACKET_IN_FORMAT    (16, NxSetPacketInFormat.class),
    NXT_PACKET_IN               (17, NxPacketIn.class);

    static Map<Integer, NxType> mapping;

    protected Class<? extends NxMessage> clazz;
    protected int type;

    /**
     * Store some information about the NX type, including wire protocol
     * type number and derived class
     *
     * @param type Wire protocol number associated with this NxType
     * @param clazz The Java class corresponding to this type of OpenFlow
     *              message
     */
    NxType(int type, Class<? extends NxMessage> clazz) {
        this.type = type;
        this.clazz = clazz;
        NxType.addMapping(this.type, this);
    }

    /**
     * Adds a mapping from type value to NxType enum
     *
     * @param i OpenFlow NX wire protocol type
     * @param t type
     */
    static public void addMapping(int i, NxType t) {
        if (mapping == null)
            mapping = new HashMap<Integer, NxType>();
        NxType.mapping.put(i, t);
    }

    /**
     * Remove a mapping from type value to NxType enum
     *
     * @param i OpenFlow NX wire protocol type
     */
    static public void removeMapping(int i) {
        mapping.remove(i);
    }

    /**
     * Given a wire protocol OpenFlow NX type number, return the NxType
     * associated with it
     *
     * @param i wire protocol number
     * @return NxType enum type
     */

    static public NxType valueOf(int i) {
        return NxType.mapping.get(i);
    }

    /**
     * @return Returns the wire protocol value corresponding to this OFType
     */
    public int getTypeValue() {
        return this.type;
    }

    /**
     * @return return the OFMessage subclass corresponding to this OFType
     */
    public Class<? extends NxMessage> toClass() {
        return clazz;
    }

}
