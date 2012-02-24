/*
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class NxSetFlowFormat extends NxMessage {

    protected boolean nxmFormat;

    // Should only be used for de-serialization.
    public NxSetFlowFormat() {
        super(NxType.NXT_SET_FLOW_FORMAT);
    }

    public NxSetFlowFormat(boolean nxm) {
        super(NxType.NXT_SET_FLOW_FORMAT);
        this.nxmFormat = nxm;
        super.setLength((short) 20);
    }

    @Override
    public void writeTo(ByteBuffer data) {
        super.writeTo(data);
        /* Strange, but Nicira-ext.h defines the flow format like this:
            enum nx_flow_format {
                NXFF_OPENFLOW10 = 0,
                NXFF_NXM = 2
            }
         */
        data.putInt(nxmFormat ? 2 : 0);
    }

    @Override
    public void readFrom(ByteBuffer data) {
        super.readFrom(data);
        int format = data.getInt();
        if (format == 2)
            nxmFormat = true;
        else if (format == 0)
            nxmFormat = false;
        else
            throw new RuntimeException("readFrom - format must 2 or 0");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        NxSetFlowFormat that = (NxSetFlowFormat) o;

        if (nxmFormat != that.nxmFormat) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (nxmFormat ? 1 : 0);
        return result;
    }
}
