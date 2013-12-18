/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data;

import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.rules.RuleResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


public abstract class Rule
        <RuleData extends Rule.Data, Self
                extends Rule<RuleData, Self>>
        extends Entity.Base<UUID, RuleData, Self> {

    public static class RuleIndexOutOfBoundsException extends Exception {
        private static final long serialVersionUID = 1L;

        /**
         * Default constructor
         */
        public RuleIndexOutOfBoundsException() {
            super();
        }

        public RuleIndexOutOfBoundsException(String message) {
            super(message);
        }

        public RuleIndexOutOfBoundsException(String message, Throwable cause) {
            super(message, cause);
        }

    }

    public enum Property {
    }

    protected Rule(Condition condition, RuleData data) {
        this(null, condition, data);
    }

    protected Rule(UUID uuid, Condition condition, RuleData data) {
        super(uuid, data);
        setCondition(condition);
    }

    public Condition getCondition() {
        return getData().condition;
    }

    public Self setCondition(Condition cond) {
        if (cond == null) {
           throw new IllegalArgumentException("Condition cannot be null");
        }
        getData().condition = cond;
        return self();
    }

    public RuleResult.Action getAction() {
        return getData().action;
    }

    public Self setAction(RuleResult.Action action) {
        getData().action = action;
        return self();
    }

    public UUID getChainId() {
        return getData().chainId;
    }

    public Self setChainId(UUID chainId) {
        getData().chainId = chainId;
        return self();
    }

    public int getPosition() {
        return getData().position;
    }

    public Self setPosition(int position) {
        getData().position = position;
        return self();
    }

    public Self setProperty(Property property, String value) {
        getData().properties.put(property.name(), value);
        return self();
    }

    public Self setProperties(Map<String, String> properties) {
        getData().properties = properties;
        return self();
    }

    public String getProperty(Property property) {
        return getData().properties.get(property.name());
    }

    public Map<String, String> getProperties() {
        return getData().properties;
    }

    public static class Data implements Comparable<Data> {

        public Condition condition;
        public RuleResult.Action action;
        public UUID chainId;
        public int position;
        public Map<String, String> properties = new HashMap<String, String>();

        @Override
        public boolean equals(Object other) {
            if (this == other)
                return true;
            if (!(other instanceof Data))
                return false;
            Data r = (Data) other;
            if (!condition.equals(r.condition))
                return false;
            if (null == action || null == r.action) {
                return action == r.action;
            } else {
                return action.equals(r.action);
            }
        }

        @Override
        public int hashCode() {
            int hash = condition.hashCode() * 23;
            if (null != action)
                hash += action.hashCode();
            return hash;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("condition=").append(condition);
            sb.append(", action=").append(action);
            sb.append(", chainId=").append(chainId);
            sb.append(", position=").append(position);
            return sb.toString();
        }

        @Override
        public int compareTo(Data rule) {
            return this.position - rule.position;
        }
    }
}
