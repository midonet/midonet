/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.Builder;

public class FlowActionOutput implements FlowAction {

    /** u32 port number. */
    private int portNumber;

    // This is used for deserialization purposes only.
    FlowActionOutput() { }

    FlowActionOutput(int portNumber) {
        this.portNumber = portNumber;
    }

    @Override
    public void serialize(Builder builder) {
        builder.addValue(portNumber);
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

    @Override
    public NetlinkMessage.AttrKey<FlowActionOutput> getKey() {
        return FlowActionAttr.OUTPUT;
    }

    @Override
    public FlowActionOutput getValue() {
        return this;
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
        return "FlowActionOutput{" +
            "portNumber=" + portNumber +
            '}';
    }
}
