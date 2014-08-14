/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage;

import java.util.List;

/**
 * An abstract base class for representing a binding between two objects. Both
 * objects have an 'ID', and one is referencing the other by holding the
 * referenced object's ID in its field. The two objects can actually be the same
 * object, recursively referencing itself. The referenced object may optionally
 * hold a 'back-reference' to the referencing object.
 *
 * A binding must also specify what should happen on deleting the referencing
 * object, which is one of the following:
 *
 *      CASCADE: Delete the bound referenced object.
 *
 *      CLEAR: Remove the bound referenced instance's back-reference to the
 *      referencing object.
 *
 *      ERROR: Do not allow the referencing object to be deleted unless all the
 *      back-references to it are removed.
 *
 * Eg. Bridge and Chain
 * A bridge's in-bound filter ID refers to a chain, and a chain has back-
 * references to all the bridges to which the chain is being applied.
 */
abstract class FieldBinding {
    public static final String ID_FIELD = "id";
    public enum DeleteAction {
        CASCADE,
        CLEAR,
        ERROR
    }

    public final DeleteAction onDeleteThis;

    public FieldBinding(DeleteAction onDeleteThis) {
        this.onDeleteThis = onDeleteThis;
    }

    /**
     * Returns a Class object for the referencing class.
     */
    abstract public Class<?> getReferencedClass();

    /**
     * Returns a delete action on deleting referring object.
     */
    public DeleteAction onDeleteThis() {
        return this.onDeleteThis;
    }

    /**
     * Tests whether the referenced class has a back reference.
     */
    abstract public boolean hasBackReference();

    /**
     * Add a back-reference to a referring object with the specified ID to the
     * referenced object and returns an updated referenced object.
     * @param referenced A referenced object.
     * @param referrerId A referring object ID.
     * @return An updated referenced object with back-refs added.
     * @throws ReferenceConflictException when adding the back-reference would
     * overwrite other back-references.
     */
    abstract public <T> T addBackReference(T referenced, Object referrerId)
            throws ReferenceConflictException;

    /**
     * Remove a back-reference to a referring object with the specified ID from the
     * referenced object, and returns an updated referenced object.
     * @param referenced A referenced object.
     * @param referrerId A referring object ID.
     * @return An updated referenced object with back-refs removed.
     */
    abstract public <T> T clearBackReference(T referenced, Object referrerId);

    /**
     * Gets the (forward) reference IDs in the referring object as a list.
     *
     * @return
     *   -If the reference ID field are empty, an empty list.
     *   -If the reference ID field is not a list, a list containing only the
     *    value.
     *   -If the reference ID field is a list, a list of its elements.
     */
    abstract public List<Object> getFwdReferenceAsList(Object referring);
}
