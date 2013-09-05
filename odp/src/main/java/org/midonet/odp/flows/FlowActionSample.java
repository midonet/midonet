/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.flows;

import java.util.List;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;
import org.midonet.odp.OpenVSwitch;

public class FlowActionSample implements FlowAction<FlowActionSample> {

    /**
     * u32 port number.
     */
    int probability;
    List<? extends FlowAction<?>> actions;


    @Override
    public void serialize(BaseBuilder<?,?> builder) {
        builder
            .addAttr(Attr.PROBABILITY, probability)
            .addAttrNested(Attr.ACTIONS)
            .addAttrs(actions)
            .build();
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            probability = message.getAttrValueInt(Attr.PROBABILITY);
            actions = message.getAttrValue(Attr.ACTIONS, FlowAction.Builder);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static class Attr<T> extends NetlinkMessage.AttrKey<T> {

        /**
         * u32 port number.
         */
        public static final Attr<Integer> PROBABILITY =
            attr(OpenVSwitch.FlowAction.SampleAttr.Probability);

        /**
         * Nested OVS_ACTION_ATTR_*.
         */
        public static final Attr<List<FlowAction<?>>> ACTIONS =
            attrNest(OpenVSwitch.FlowAction.SampleAttr.Actions);

        private Attr(int id, boolean nested) {
            super(id);
        }

        static <T> Attr<T> attr(int id) {
            return new Attr<T>(id, false);
        }

        static <T> Attr<T> attrNest(int id) {
            return new Attr<T>(id, true);
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowActionSample> getKey() {
        return FlowActionAttr.SAMPLE;
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

    public List<? extends FlowAction<?>> getActions() {
        return actions;
    }

    public FlowActionSample setActions(List<? extends FlowAction<?>> actions) {
        this.actions = actions;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowActionSample that = (FlowActionSample) o;

        if (probability != that.probability) return false;
        if (actions != null ? !actions.equals(
            that.actions) : that.actions != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = probability;
        result = 31 * result + (actions != null ? actions.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "FlowActionSample{" +
            "probability=" + probability +
            ", actions=" + actions +
            '}';
    }
}
