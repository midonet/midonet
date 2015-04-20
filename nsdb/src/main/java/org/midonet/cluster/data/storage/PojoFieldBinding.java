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
package org.midonet.cluster.data.storage;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import static org.midonet.Util.uncheckedCast;

/**
 * Represents a binding between a field in some POJO and a field in another /
 * the same POJO. For each PojoFieldBinding, there must be a referencing POJO,
 * whose field the binding binds to another field in another or possibly the
 * same type of message.
 *
 * Both POJOs must have an 'id' field. The 'thisField' field of the referencing
 * POJO is a single / list field of the ID of the referenced 'thatClass' POJO.
 * If 'thatField' is not empty, the field with the specified name in the
 * 'thatClass' POJO is a single / list 'back-references' to POJOs of the same
 * type as the referencing POJO.
 *
 * 'onDeleteThis' specifies what should happen on deleting the referencing POJO,
 * and is an enum field of the following values.
 *
 *      CASCADE: Delete the bound thatClass instance.
 *
 *      CLEAR: Remove the bound thatClass instance's reference to this
 *      thisClass instance. If thatFieldis a list, then this will remove the
 *      thisClass instance's id from the thatClass instance's list field named
 *      by thatField. Otherwise it will set that field to null.
 *
 *      ERROR: Do not allow an instance of thisClass to be deleted unless the
 *      value of the thisField field is either null or an empty list.
 *
 * Example from  ZookeeperObjectMapperTest: Bridge and Chain
 * Referencing message: Bridge
 * ProtoFieldBinding
 *     - thisField: 'inChainId' Field
 *     - onDeleteThis: CLEAR
 *     - thatClass: Chain
 *     - thatField: 'bridgeIds' Field
 */
class PojoFieldBinding extends FieldBinding {
    public final Field thisField;
    public final Class<?> thatClass;
    public final Field thatField;

    private PojoFieldBinding(Field thisField,
                        Class<?> thatClass, Field thatField,
                        DeleteAction onDeleteThis) {
        super(onDeleteThis);
        this.thatClass = thatClass;
        this.thisField = thisField;
        this.thatField = thatField;
    }

    /**
     * Declares a binding which create, update, and delete operations will use
     * to maintain referential integrity. The bindings are bidirectional, and
     * it does not matter which field is "left" and which is "right".
     *
     * Both classes must have a field called "id". This field may be of any
     * type provided that the referencing field in the other class is of
     * the same type.
     *
     * @param leftClass
     *      First class to bind.
     *
     * @param leftFieldName
     *      Name of the field on leftClass which references the ID of an
     *      instance of rightClass. Must be either the same type as
     *      rightClass's id field, or a List of that type.
     *
     * @param onDeleteLeft
     *      Indicates what should be done on an attempt to delete an instance
     *      of leftClass.
     *
     * @param rightClass See leftClass.
     * @param rightFieldName See leftFieldName.
     * @param onDeleteRight See onDeleteLeft.
     */
    public static ListMultimap<Class<?>,FieldBinding> createBindings(
            Class<?> leftClass,
            String leftFieldName,
            FieldBinding.DeleteAction onDeleteLeft,
            Class<?> rightClass,
            String rightFieldName,
            FieldBinding.DeleteAction onDeleteRight) {
        assert(leftFieldName != null || rightFieldName != null);

        Field leftIdField = getField(leftClass, ID_FIELD);
        Field rightIdField = getField(rightClass, ID_FIELD);
        Field leftRefField = getField(leftClass, leftFieldName);
        Field rightRefField = getField(rightClass, rightFieldName);

        checkTypeCompatibilityForBinding(leftIdField, rightRefField);
        checkTypeCompatibilityForBinding(rightIdField, leftRefField);

        ListMultimap<Class<?>, FieldBinding> bindings =
                ArrayListMultimap.create();
        if (leftRefField != null)
            bindings.put(leftClass, new PojoFieldBinding(
                    leftRefField, rightClass, rightRefField, onDeleteLeft));
        if (rightRefField != null && leftClass != rightClass)
            bindings.put(rightClass, new PojoFieldBinding(
                    rightRefField, leftClass, leftRefField, onDeleteRight));

        return bindings;
    }

    private static String getQualifiedName(Field f) {
        return f.getDeclaringClass().getSimpleName() + '.' + f.getName();
    }

    private static boolean isList(Field f) {
        return List.class.isAssignableFrom(f.getType());
    }

    @Override
    public Class<?> getReferencedClass() {
        return this.thatClass;
    }

    @Override
    public boolean hasBackReference() {
        return this.thatField != null;
    }

    private boolean isBackReferenceList() {
        return isList(this.thatField);
    }

    @Override
    public <T> T addBackReference(
            T referenced, Object referencedId, Object referrerId)
            throws ReferenceConflictException {
        if (!this.hasBackReference()) return referenced;

        if (this.isBackReferenceList()) {
            addToList(referenced, this.thatField, referrerId);
        } else {
            Object curFieldVal = getValue(referenced, this.thatField);
            if (curFieldVal == null) {
                setValue(referenced, this.thatField, referrerId);
            } else {
                // Reference conflict. Throws an exception.
                throw new ReferenceConflictException(
                        referenced.getClass().getSimpleName(),
                        referencedId.toString(),
                        this.thatField.getName(),
                        this.thisField.getDeclaringClass().getSimpleName(),
                        curFieldVal.toString());
            }
        }
        return referenced;
    }

    @Override
    public <T> T clearBackReference(T referenced, Object referrerId) {
        if (!this.hasBackReference()) return referenced;

        if (this.isBackReferenceList()) {
            removeFromList(referenced, this.thatField, referrerId);
        } else {
            Object curFieldVal = getValue(referenced,this.thatField);
            if (Objects.equals(curFieldVal, referrerId))
                setValue(referenced, this.thatField, null);
        }
        return referenced;
    }

    @Override
    public List<Object> getFwdReferenceAsList(Object referring) {
        Object val = getValue(referring, this.thisField);
        if (val == null)
            return Collections.emptyList();
        else if (val instanceof List)
            return uncheckedCast(val);
        else
            return Arrays.asList(val);
    }

    @Override
    public String toString() {
        return String.format("%s -> %s.%s (%s)", thisField.getName(),
                thatClass.getSimpleName(), thatField.getName(), onDeleteThis);
    }

    /*
     * Converts Field.get()'s IllegalAccessException to an
     * InternalObjectMapperException.
     */
    private static Object getValue(Object o, Field f) {
        try {
            return f.get(o);
        } catch (IllegalAccessException ex) {
            throw new InternalObjectMapperException(ex);
        }
    }

    /*
     * Convert Field.set()'s IllegalAccessException to an
     * InternalObjectMapperException.
     */
    private static void setValue(Object o, Field f, Object val) {
        try {
            f.set(o, val);
        } catch (IllegalAccessException ex) {
            throw new InternalObjectMapperException(ex);
        }
    }

    /*
     * Adds val to o's field f. F's type must be a subclass of List.
     */
    private static void addToList(Object o, Field f, Object val) {

        List<Object> list = uncheckedCast(getValue(o, f));
        if (list == null) {
            list = new ArrayList<>();
            setValue(o, f, list);
        }
        list.add(val);
    }

    /**
     * Removes a value from o's field f. F's type must be a subclass of List.
     */
    private static void removeFromList(Object o, Field f, Object val) {
        List<?> list = (List<?>)getValue(o, f);
        list.remove(val);
    }

    /* Checks type compatibility between the ID getter and ID reference
     * fields.
     */
    private static void checkTypeCompatibilityForBinding(Field id, Field ref) {
        Class<?> refType = ref.getType();
        if (isList(ref)) {
            ParameterizedType pt = (ParameterizedType)ref.getGenericType();
            refType = (Class<?>)pt.getActualTypeArguments()[0];
        }

        if (id.getType() != refType) {
            String idName = getQualifiedName(id);
            String refName = getQualifiedName(ref);
            String idTypeName = id.getType().getSimpleName();
            throw new IllegalArgumentException(
                    "Cannot bind "+refName+" to "+idName+". " +
                    "Since "+idName+"'s type is "+idTypeName+", " +
                    refName+"'s type must be either "+idTypeName+" or " +
                    "List<"+idTypeName+">.");
        }
    }

    /**
     * Converts Class.getDeclaredField()'s NoSuchFieldException to an
     * IllegalArgumentException.
     */
    private static Field getField(Class<?> clazz, String name) {
        if (name == null)
            return null;

        try {
            Field f = clazz.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
