/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A notification event for an entity set.
 */
public class EntityIdSetEvent<KEY> {
    public enum Type {
        CREATE, DELETE, STATE
    }

    public final KEY value;
    public final Type type;

    private EntityIdSetEvent(KEY value, Type type) {
        this.value = value;
        this.type = type;
    }

    public static <KEY> EntityIdSetEvent<KEY> create(KEY id) {
        return new EntityIdSetEvent<>(id, Type.CREATE);
    }

    public static <KEY> EntityIdSetEvent<KEY> delete(KEY id) {
        return new EntityIdSetEvent<>(id, Type.DELETE);
    }

    public static <KEY> EntityIdSetEvent<KEY> state(KEY set) {
        return new EntityIdSetEvent<>(set, Type.STATE);
    }

    public static <KEY> List<EntityIdSetEvent<KEY>> state(Collection<KEY> set) {
        List<EntityIdSetEvent<KEY>> evs = new ArrayList<>(set.size());
        for (KEY k : set) {
            evs.add(new EntityIdSetEvent<>(k, Type.STATE));
        }
        return evs;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (null == obj || getClass() != obj.getClass())
            return false;

        EntityIdSetEvent event = (EntityIdSetEvent) obj;

        return Objects.equals(type, event.type) &&
               Objects.equals(value, event.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value);
    }
}
