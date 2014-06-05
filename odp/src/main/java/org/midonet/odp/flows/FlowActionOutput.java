/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.NetlinkMessage;

public class FlowActionOutput implements FlowAction {

    /** u32 port number. */
    private int portNumber;

    // This is used for deserialization purposes only.
    FlowActionOutput() { }

    FlowActionOutput(int portNumber) {
        this.portNumber = portNumber;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putInt(portNumber);
        return 4;
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            portNumber = message.getInt();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public short attrId() {
        return FlowActionAttr.OUTPUT.getId();
    }

    public int getPortNumber() {
        return portNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowActionOutput that = (FlowActionOutput) o;

        return portNumber == that.portNumber;
    }

    @Override
    public int hashCode() {
        return portNumber;
    }

    @Override
    public String toString() {
        return "FlowActionOutput{portNumber=" + portNumber + '}';
    }
}
