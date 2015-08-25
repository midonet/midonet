/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.midonet.netlink.AttributeHandler;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.Reader;
import org.midonet.netlink.Writer;
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

    /** stateless serialiser and deserialiser of ovs FlowAction classes. Used
     *  as a typeclass with NetlinkMessage.writeAttr() and writeAttrSet()
     *  for assembling ovs requests. */
    public static final Writer<FlowAction> writer = new Writer<FlowAction>() {
        public short attrIdOf(FlowAction value) {
            return value.attrId();
        }
        public int serializeInto(ByteBuffer receiver, FlowAction value) {
            return value.serializeInto(receiver);
        }
    };

    public static final Reader<List<FlowAction>> reader =
        new Reader<List<FlowAction>>() {
            public List<FlowAction> deserializeFrom(ByteBuffer buffer) {
                final List<FlowAction> actions = new ArrayList<FlowAction>();
                AttributeHandler handler = new AttributeHandler() {
                    public void use(ByteBuffer buf, short id) {
                        FlowAction a = newBlankInstance(id);
                        if (a == null)
                            return;
                        a.deserializeFrom(buf);
                        actions.add(a);
                    }
                };
                NetlinkMessage.scanAttributes(buffer, handler);
                return actions;
            }
        };

    public static FlowAction newBlankInstance(short type) {
        switch (NetlinkMessage.unnest(type)) {

            case OpenVSwitch.FlowAction.Attr.Output:
                return new FlowActionOutput();

            case OpenVSwitch.FlowAction.Attr.Userspace:
                return new FlowActionUserspace();

            case OpenVSwitch.FlowAction.Attr.Set:
                return new FlowActionSetKey();

            case OpenVSwitch.FlowAction.Attr.PushVLan:
                return new FlowActionPushVLAN();

            case OpenVSwitch.FlowAction.Attr.PopVLan:
                return new FlowActionPopVLAN();

            case OpenVSwitch.FlowAction.Attr.Sample:
                return new FlowActionSample();

            default:
                return null;
        }
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
            a = FlowActions.newBlankInstance((short)(1 + rand.nextInt(6)));
        }
        if (a instanceof Randomize) {
            ((Randomize)a).randomize();
        } else {
            byte[] bytes = new byte[1024];
            rand.nextBytes(bytes);
            a.deserializeFrom(ByteBuffer.wrap(bytes));
        }
        return a;
    }

    public static Random rand = new Random();
}
