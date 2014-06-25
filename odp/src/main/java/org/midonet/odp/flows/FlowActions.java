/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.OpenVSwitch;

/**
 * Builder class to allow easier building of FlowAction instances.
 */
public class FlowActions {

    public static FlowActionOutput output(int portNumber) {
        return new FlowActionOutput(portNumber);
    }

    public static FlowActionUserspace userspace(int uplinkPid) {
        return new FlowActionUserspace(uplinkPid);
    }

    public static FlowActionUserspace userspace(int uplinkPid, long userData) {
        return new FlowActionUserspace(uplinkPid, userData);
    }

    public static FlowActionSetKey setKey(FlowKey flowKey) {
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
                                          List<FlowAction> actions) {
        return new FlowActionSample(probability, actions);
    }

    public static List<FlowAction> buildFrom(ByteBuffer buf) {
        return NetlinkMessage.getAttrValue(buf,
                                           OpenVSwitch.Flow.Attr.Actions,
                                           FlowAction.Builder);
    }

    public static List<FlowAction> randomActions() {
        List<FlowAction> actions = new ArrayList<>();
        while (rand.nextInt(100) >= 30 && actions.size() <= 10) {
            actions.add(randomAction());
        }
        return actions;
    }

    public static FlowAction randomAction() {
        FlowAction a = null;
        while (a == null) {
            a = FlowAction.Builder.newInstance((short)(1 + rand.nextInt(6)));
        }
        if (a instanceof Randomize) {
            ((Randomize)a).randomize();
        } else {
            byte[] bytes = new byte[1024];
            rand.nextBytes(bytes);
            a.deserialize(ByteBuffer.wrap(bytes));
        }
        return a;
    }

    public static Random rand = new Random();
}
