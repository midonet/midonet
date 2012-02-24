/*
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFType;
import org.openflow.protocol.OFVendor;
import org.openflow.util.U16;

/**
 * This class directly subclasses OFMessage instead of OFVendor because the
 * latter has a data field that holds the vendor message's data. Trying to
 * ignore that field is difficult (breaks equals, hashCode, and readFrom).
 */
public class NxMessage extends OFMessage {

    public static int MINIMUM_LENGTH = 12;
    public final static int NX_VENDOR_ID  = 0x00002320;
    protected NxType subtype;

    // Used for de-serialization, e.g. by NxMessage.fromOFVendor
    protected NxMessage() {
    }

    protected NxMessage(NxType subtype) {
        this.type = OFType.VENDOR;
        this.length = U16.t(MINIMUM_LENGTH);
        this.subtype = subtype;
    }

    public NxType getNxType() {
        return subtype;
    }

    @Override
    public void readFrom(ByteBuffer data) {
        super.readFrom(data);
        int vendor = data.getInt();
        if (vendor != NX_VENDOR_ID)
            throw new RuntimeException("Expected NX_VENDOR_ID 0x2320 but got " +
                    "vendor " + Integer.toHexString(vendor));
        subtype = NxType.valueOf(data.getInt());
    }

    @Override
    public void writeTo(ByteBuffer data) {
        super.writeTo(data);
        data.putInt(NX_VENDOR_ID);
        data.putInt(subtype.getTypeValue());
    }

    @Override
    public String toString() {
        return "NxMessage{" + super.toString() +
                ", subtype=" + subtype +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        NxMessage nxMessage = (NxMessage) o;

        if (subtype != nxMessage.subtype) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (subtype != null ? subtype.hashCode() : 0);
        return result;
    }

    public static NxMessage fromOFVendor(OFVendor vm) {
        ByteBuffer buff = ByteBuffer.wrap(vm.getData());
        int subtype = buff.getInt();
        NxType nxType = NxType.valueOf(subtype);
        if (null == nxType)
            throw new RuntimeException("fromOFVendor - unrecognized NX " +
                    "subtype {}" + subtype);
        Class<? extends NxMessage> c = nxType.toClass();
        NxMessage nxm;
        try {
            nxm = c.getConstructor(new Class[]{}).newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        buff = ByteBuffer.allocate(vm.getLength());
        vm.writeTo(buff);
        buff.flip();
        nxm.readFrom(buff);
        return nxm;
    }
}
