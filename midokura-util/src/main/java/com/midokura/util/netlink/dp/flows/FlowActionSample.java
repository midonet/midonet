/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.flows;

import java.util.List;

import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.messages.BaseBuilder;

public class FlowActionSample implements FlowAction<FlowActionSample> {

    /**
     * u32 port number.
     */
    int probability;
    List<? extends FlowAction> actions;


    @Override
    public void serialize(BaseBuilder builder) {
        builder
            .addAttr(Attr.PROBABILITY, probability)
            .addAttrNested(Attr.ACTIONS)
                .addAttrs(actions)
            .build();
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
//            portNumber = message.getInt();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static class Attr<T> extends NetlinkMessage.AttrKey<T> {

        /**
         * u32 port number.
         */
        public static final Attr<Integer> PROBABILITY = attr(1);

        /**
         * Nested OVS_ACTION_ATTR_*.
         */
        public static final Attr<List<FlowAction>> ACTIONS = attr(2);

        public Attr(int id) {
            super(id);
        }

        static <T> Attr<T> attr(int id) {
            return new Attr<T>(id);
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowActionSample> getKey() {
        return FlowActionKey.SAMPLE;
    }

    @Override
    public FlowActionSample getValue() {
        return this;
    }

    public int getProbability() {
        return probability;
    }

    public FlowActionSample setProbability(int probability) {
        this.probability = probability;
        return this;
    }

    public List<? extends FlowAction> getActions() {
        return actions;
    }

    public FlowActionSample setActions(List<? extends FlowAction> actions) {
        this.actions = actions;
        return this;
    }
}
