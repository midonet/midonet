/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage;

import java.lang.reflect.Field;

class FieldBinding {

    public enum DeleteAction {
        CASCADE,
        CLEAR,
        ERROR
    }

    public final Field thisField;
    public final DeleteAction onDeleteThis;
    public final Class<?> thatClass;
    public final Field thatField;

    public FieldBinding(Field thisField,
                        Class<?> thatClass, Field thatField,
                        DeleteAction onDeleteThis) {
        this.thatClass = thatClass;
        this.thisField = thisField;
        this.thatField = thatField;
        this.onDeleteThis = onDeleteThis;
    }

    public String toString() {
        return String.format("%s -> %s.%s (%s)", thisField.getName(),
                thatClass.getSimpleName(), thatField.getName(), onDeleteThis);
    }
}
