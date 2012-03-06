/*
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

import org.openflow.protocol.action.OFActionVendor;

public class NxAction extends OFActionVendor {

    public final static int NX_VENDOR_ID  = 0x00002320;

    protected NxActionType subtype;

    public NxAction() {
    }

    protected NxAction(NxActionType subtype) {
        super.setVendor(NX_VENDOR_ID);
        this.subtype = subtype;
    }

    public NxActionType getSubtype() {
        return subtype;
    }

    @Override
    public void readFrom(ByteBuffer data) {
        super.readFrom(data);
        subtype = NxActionType.valueOf(data.getShort());
    }

    @Override
    public void writeTo(ByteBuffer data) {
        super.writeTo(data);
        data.putShort(subtype.getTypeValue());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        NxAction nxAction = (NxAction) o;

        if (subtype != nxAction.subtype) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + subtype.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "NxAction{" +
                "subtype=" + subtype +
                '}';
    }
}
