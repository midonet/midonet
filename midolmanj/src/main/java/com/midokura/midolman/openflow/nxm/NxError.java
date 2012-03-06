/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

import org.openflow.protocol.OFError;

public class NxError {

    /* ofp_error msg 'type' for Nicira's "error vendor extension" */
    public static final short NXET_VENDOR = (short)0xb0c2;
    /* ofp_error msg 'code' values for NXET_VENDOR. */
    public static final short NXVC_VENDOR_ERROR = (short)0;

    public static boolean isNxErrorExtension(OFError errMsg) {
        return errMsg.getErrorType() == NXET_VENDOR &&
                errMsg.getErrorCode() == NXVC_VENDOR_ERROR;
    }

    public static NxError fromOFError(OFError errMsg) {
        NxError err = new NxError();
        err.readFrom(ByteBuffer.wrap(errMsg.getError()));
        return err;
    }

    protected int vendorId;     /* Vendor ID as in struct ofp_vendor_header. */
    protected short type;       /* Vendor-defined type. */
    protected short code;       /* Vendor-defined subtype. */
    /* At least the first 64 bytes of the failed request. */
    protected byte[] failedReq;

    public int getVendorId() {
        return vendorId;
    }

    public short getType() {
        return type;
    }

    public short getCode() {
        return code;
    }

    public byte[] getFailedReq() {
        return failedReq;
    }

    public void readFrom(ByteBuffer data) {
        vendorId = data.getInt();
        type = data.getShort();
        code = data.getShort();
        failedReq = new byte[data.limit() - data.position()];
        data.get(failedReq);
    }

    public void writeTo(ByteBuffer data) {
        data.putInt(vendorId);
        data.putShort(type);
        data.putShort(code);
        if (null != failedReq)
            data.put(failedReq);
    }

    @Override
    public String toString() {
        return "NxError{" +
                "vendorId=0x" + Integer.toHexString(vendorId) +
                ", type=" + type +
                ", code=" + code +
                '}';
    }
}
