/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.cluster.data;

import org.midonet.midolman.rules.Condition;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TraceCondition
    extends Entity.Base<UUID, TraceCondition.Data, TraceCondition>
{
    public TraceCondition(UUID id, Condition condition) {
        super(id, new Data());
        setCondition(condition);
    }

    @Override
    protected TraceCondition self() {
        return this;
    }

    public Condition getCondition() {
        return getData().condition;
    }

    public TraceCondition setCondition(Condition condition) {
        if (condition == null) {
            throw new IllegalArgumentException("Condition cannot be null");
        }
        getData().condition = condition;
        return self();
    }

    public static class Data {
        public Condition condition;

        @Override
        public boolean equals(Object other) {
            if (this == other)
                return true;
            if (!(other instanceof Data))
                return false;
            Data otherData = (Data) other;
            if (!condition.equals(otherData.condition))
                return false;
            return true;
        }

        @Override
        public int hashCode() {
            int hash = condition.hashCode() * 29;
            return hash;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("condition=").append(condition);
            return sb.toString();
        }
    }
}
