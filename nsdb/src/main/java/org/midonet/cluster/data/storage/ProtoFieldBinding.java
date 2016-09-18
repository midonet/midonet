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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

import org.midonet.cluster.data.package$;

import static org.midonet.Util.uncheckedCast;

/**
 * Represents a binding between a field of a protocol buffer message to another
 * / the same protocol buffer message. For each ProtoFieldBinding, there must be
 * a referencing protocol buffer message, whose field the binding binds to
 * another or possibly the same type of message.
 *
 * Both messages must have an 'id' field. The 'thisField' field of the
 * referencing message is a scalar / repeated field of the ID of the referenced
 * 'thatMessage' message. If 'thatField' is not empty, the field with the
 * specified name in the 'thatMessage' message is a scalar / repeated
 * 'back-references' to messages of the same type as the referencing message.
 *
 * 'onDeleteThis' specifies what should happen on deleting the referencing
 * message, and is an enum field of the following values.
 *
 *      CASCADE: Delete the bound rightMessage instance.
 *
 *      CLEAR: Remove the bound rightMessage instance's reference to this
 *      leftMessage instance. If rightField is a list, then this will remove the
 *      leftMessage instance's id from the rightMessage instance's list field
 *      named by rightField. Otherwise it will set that field to null.
 *
 *      ERROR: Do not allow an instance of leftMessage to be deleted unless
 *      the value of the field named by leftField is either null or an empty
 *      list.
 *
 * Eg. Bridge and Chain
 * Referencing message: Devices.Bridge
 * ProtoFieldBinding
 *     - thisField: 'inbound_filter_id'
 *     - onDeleteThis: CLEAR
 *     - thatMessage: Devices.Chain
 *     - thatField: 'bridge_ids'
 */
class ProtoFieldBinding extends FieldBinding {
    private final FieldDescriptor thisField;
    private final Message thatMessage;
    private final FieldDescriptor thatField;

    private ProtoFieldBinding(FieldDescriptor thisField,
                              Message thatMessage,
                              FieldDescriptor thatField,
                              DeleteAction onDeleteThis) {
        super(onDeleteThis);
        this.thatMessage = thatMessage;
        this.thisField = thisField;
        this.thatField = thatField;
    }

    /**
     * Declares a binding between two protocol buffer fields. Bindings are
     * bidirectional, and it does not matter which message is "left" and which
     * is "right".
     *
     * Both messages must have a field called "id". This field may be of any
     * type provided that the referencing field in the other message is of
     * the same type.
     *
     * @param leftClass The first class to bind.
     * @param leftFieldName The name of the field on leftClass which
     *     references the ID of an instance of rightClass. Must be the same
     *     type as rightClass's id field, or a List of that type.
     * @param onDeleteLeft Indicates what should be done on an attempt to delete
     *     an instance of leftClass.
     * @param rightClass See leftClass.
     * @param rightFieldName See leftFieldName.
     * @param onDeleteRight See onDeleteLeft.
     */
    public static ListMultimap<Class<?>, FieldBinding> createBindings(
             Class<?> leftClass,
             String leftFieldName,
             FieldBinding.DeleteAction onDeleteLeft,
             Class<?> rightClass,
             String rightFieldName,
             FieldBinding.DeleteAction onDeleteRight) {
        Message leftMessage = getDefaultMessage(leftClass);
        Message rightMessage = getDefaultMessage(rightClass);

        FieldDescriptor rightField = checkTypeCompatibilityForBinding(
                leftMessage, rightMessage, rightFieldName);
        FieldDescriptor leftField = checkTypeCompatibilityForBinding(
            rightMessage, leftMessage, leftFieldName);

        ListMultimap<Class<?>, FieldBinding> bindings =
                ArrayListMultimap.create();
        if (leftField != null)
            bindings.put(leftMessage.getClass(),
                         new ProtoFieldBinding(leftField,
                                               rightMessage,
                                               rightField,
                                               onDeleteLeft));
        if (rightField != null && leftMessage != rightMessage)
            bindings.put(rightMessage.getClass(),
                         new ProtoFieldBinding(rightField,
                                               leftMessage,
                                               leftField,
                                               onDeleteRight));

        return bindings;
    }

    /* Checks type compatibility between the ID field of the referencing message
     * and the back-reference field of the referenced message and returns a
     * FieldDescriptor for the back-reference.
     */
    private static FieldDescriptor checkTypeCompatibilityForBinding(
            Message thisMsg, Message referringMsg, String refFieldName) {
        // Get the ID field first to test its existence.
        FieldDescriptor thisIdField = getMessageField(thisMsg, ID_FIELD);

        if (refFieldName == null) {
            return null;  // No back referencing.
        }

        FieldDescriptor referenceField = getMessageField(referringMsg, refFieldName);

        if (!areCompatible(thisIdField, referenceField)) {
            String idName = thisIdField.getFullName();
            String refName = referenceField.getFullName();
            String idTypeName = thisIdField.getType().toString();
            throw new IllegalArgumentException(
                    "Cannot bind "+refName+" to "+idName+". " +
                    "Since "+idName+"'s type is "+idTypeName+", "+
                    refName+"'s type must be either scalar or repeated "+
                    idTypeName+" field.");
        }

        return referenceField;
    }

    private static Message getDefaultMessage(Class<?> clazz) {
        assert(Message.class.isAssignableFrom(clazz));
        try {
            Method m = clazz.getMethod("getDefaultInstance");
            return uncheckedCast(m.invoke(null));
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                "Could not statically invoke getDefaultInstanceForType() " +
                "for class " + clazz.getName() + ".");
        }
    }

    static FieldDescriptor getMessageField(Class<?> clazz, String fieldName) {
        return getMessageField(getDefaultMessage(clazz), fieldName);
    }

    /* Looks up a FieldDescriptor of the given message with a specified field
     * name. If one doesn't exist, throws an IllegalArgumentException.
     */
    private static FieldDescriptor getMessageField(Message message,
                                                   String fieldName) {
        Descriptor messageDesc = message.getDescriptorForType();
        FieldDescriptor field = messageDesc.findFieldByName(fieldName);
        if (field == null) {
            throw new IllegalArgumentException(
                    messageDesc.getName()+" does not have "+fieldName+
                    " field.");
        }
        return field;
    }

    /* Tests if two scalar / repeated fields contain the compatible types. */
    private static boolean areCompatible(FieldDescriptor field0,
                                         FieldDescriptor field1) {
        return field0.getType() == field1.getType() &&
               (field0.getType() != FieldDescriptor.Type.MESSAGE ||
                field0.getMessageType() == field1.getMessageType());
    }

    @Override
    public Class<?> getReferencedClass() {
        return this.thatMessage.getClass();
    }

    @Override
    public boolean hasBackReference() {
        return this.thatField != null;
    }

    @Override
    public String toString() {
        return String.format("%s -> %s.%s (%s)",
                             thisField,
                             thatMessage.getDescriptorForType().getFullName(),
                             thatField,
                             onDeleteThis);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T addBackReference(
            T referenced, Object referencedId, Object referrerId)
            throws ReferenceConflictException {
        if (this.thatField == null) return referenced;
        assert(referenced instanceof Message);

        Message referencedMsg = (Message) referenced;
        Message.Builder updateBuilder = referencedMsg.toBuilder();
        if (this.thatField.isRepeated()) {
            updateBuilder.addRepeatedField(this.thatField, referrerId);
        } else {
            if (referencedMsg.hasField(this.thatField)) {
                // Even if the field contains the same ID, throws an exception
                // because the referencing Object may hold more than 1 reference
                // to this object, in which case we cannot track which reference
                // to clear if we allow both references.
                throw new ReferenceConflictException(
                        referencedMsg.getClass().getSimpleName(),
                        package$.MODULE$.getIdString(referencedId),
                        this.thatField.getName(),
                        this.thisField.getContainingType().getName(),
                        package$.MODULE$.getIdString(
                            referencedMsg.getField(this.thatField)));
            }
            updateBuilder.setField(this.thatField, referrerId);
        }
        return (T) updateBuilder.build();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T clearBackReference(T referenced, Object referrerId) {
        if (this.thatField == null) return referenced;
        assert(referenced instanceof Message);

        Message referencedMsg = (Message) referenced;
        Message.Builder updateBuilder = referencedMsg.toBuilder();
        if (this.thatField.isRepeated()) {
            updateBuilder.clearField(thatField);
            int repeatedFldSize =
                    referencedMsg.getRepeatedFieldCount(this.thatField);
            boolean itemRemoved = false;
            for (int i = 0; i < repeatedFldSize; i++) {
                Object val = referencedMsg.getRepeatedField(this.thatField, i);
                if (!itemRemoved && val.equals(referrerId)) {
                    // Delete only the first reference found.
                    itemRemoved = true;
                    continue;
                }
                updateBuilder.addRepeatedField(this.thatField, val);
            }
        } else {
            if (referencedMsg.hasField(this.thatField) &&
                Objects.equals(referrerId,
                               referencedMsg.getField(this.thatField))) {
                updateBuilder.clearField(this.thatField);
            }
        }
        return (T) updateBuilder.build();
    }

    @Override
    public List<Object> getFwdReferenceAsList(Object referring) {
        ArrayList<Object> fwdRefs = new ArrayList<>();
        Message referringMsg = (Message) referring;
        if (this.thisField.isRepeated()) {
            int repeatedFldSize =
                    referringMsg.getRepeatedFieldCount(this.thisField);
            for (int i = 0; i < repeatedFldSize; i++) {
                fwdRefs.add(referringMsg.getRepeatedField(this.thisField, i));
            }
        } else {
            if (referringMsg.hasField(this.thisField))
                fwdRefs.add(referringMsg.getField(this.thisField));
        }

        return fwdRefs;
    }
}
