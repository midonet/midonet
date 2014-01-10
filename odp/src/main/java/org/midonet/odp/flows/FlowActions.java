/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.util.List;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.family.FlowFamily;

/**
 * Builder class to allow easier building of FlowAction instances.
 */
public class FlowActions {

    public static FlowActionOutput output(int portNumber) {
        return new FlowActionOutput(portNumber);
    }

    public static FlowActionUserspace userspace() {
        return new FlowActionUserspace(0);
    }

    public static FlowActionUserspace userspace(int uplinkPid) {
        return new FlowActionUserspace(uplinkPid);
    }

    public static FlowActionUserspace userspace(int uplinkPid, long userData) {
        return new FlowActionUserspace(uplinkPid, userData);
    }

    public static FlowActionSetKey setKey(FlowKey<?> flowKey) {
        return new FlowActionSetKey(flowKey);
    }

    public static FlowActionPushVLAN pushVLAN(short tagControlIdentifier) {
        return new FlowActionPushVLAN(tagControlIdentifier);
    }

    public static FlowActionPushVLAN pushVLAN(short tagControlIdentifier,
                                              short tagProtocolId) {
        return new FlowActionPushVLAN(tagControlIdentifier, tagProtocolId);
    }

    public static FlowActionPopVLAN popVLAN() {
        return new FlowActionPopVLAN();
    }

    public static FlowActionSample sample(int probability,
                                          List<FlowAction<?>> actions) {
        return new FlowActionSample(probability, actions);
    }

    public static List<FlowAction<?>> buildFrom(NetlinkMessage msg) {
        return msg.getAttrValue(FlowFamily.AttrKey.ACTIONS, FlowAction.Builder);
    }
}
