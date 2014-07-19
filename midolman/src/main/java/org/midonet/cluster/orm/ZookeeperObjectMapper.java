/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.orm;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.inject.Inject;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.orm.FieldBinding.DeleteAction;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

/**
 * Object mapper that uses Zookeeper as a data store. Maintains referential
 * integrity through the use of field bindings, which must be declared
 * prior to any CRUD operations through the use of declareBinding().
 * For example:
 *
 * declareBinding(Port.class, "bridgeId", CLEAR,
 *                Bridge.class, "portIds", ERROR);
 *
 * This indicates that Port.bridgeId is a reference to Bridge.id
 * field, and that Bridge.portIds is a list of references to Port.id.
 * Each named field is assumed to be a reference (or list of references)
 * to the other classes "id" field (all objects must have a field named
 * "id", although it may be of any type.
 *
 * Whether the specified field is a single reference or list of references
 * is determined by reflectively examining the field to see whether its
 * type implements java.util.List.
 *
 * Consequently, when a port is created or updated with a new bridgeId
 * value, its id will be added to the corresponding bridge's portIds list.
 * CLEAR indicates that when a port is deleted its ID will be removed
 * from the portIds list of the bridge referenced by its bridgeId field.
 *
 * Likewise, when a bridge is created, the bridgeId field of any ports
 * referenced by portIds will be set to that bridge's ID, and when a bridge
 * is updated, ports no longer referenced by portIds will have their
 * bridgeId fields cleared, and ports newly referenced will have their
 * bridgeId fields set to the bridge's id. ERROR indicates that it is an
 * error to attempt to delete a bridge while its portIds field contains
 * references (i.e., while it has ports).
 *
 * Note that the Midonet API does not actually allow a Bridge's portIds
 * to be set directly. However, this restriction is not enforced by the
 * ObjectMapper.
 *
 * Furthermore, if an object has a single-reference (non-list) field with
 * a non-null value, it is an error to create or update a third object in
 * a way that would cause that reference to be overwritten. For example, if
 * a port has a non-null bridge ID, then it is an error to attempt to create
 * another bridge whose portIds field contains that port's ID, as this would
 * effectively steal the port away from another bridge.
 *
 * A binding may be used to link two instances of the same type, as in the
 * case of linking ports:
 *
 * declareBinding(Port.class, "peerId", CLEAR,
 *               Port.class, "peerId", CLEAR);
 */
public class ZookeeperObjectMapper {

    private final static Logger log =
            LoggerFactory.getLogger(ZookeeperObjectMapper.class);

    private final ListMultimap<Class<?>, FieldBinding> allBindings =
            ArrayListMultimap.create();

    private final ZkManager zk;
    private final Serializer serializer;
    private final String basePath;

    @Inject
    public ZookeeperObjectMapper(ZkManager zk,
                                 Serializer serializer,
                                 ZookeeperConfig config) {
        this.zk = zk;
        this.serializer = serializer;
        this.basePath = config.getMidolmanRootKey();
    }

    /**
     * Manages objects referenced by the primary target of a create, update,
     * or delete operation.
     *
     * Caches all objects loaded during the operation. This is necessary
     * because an object may reference another object more than once. If we
     * reload the object from Zookeeper to add the second, backreference, the
     * object loaded from Zookeeper will not have the first backreference
     * added. Since updates are not incremental, the first backreference will
     * be lost.
     */
    private class ReferencedObjManager {

        private class Key {

            Class<?> clazz;
            Object id;

            Key(Class<?> clazz, Object id) {
                this.clazz = clazz;
                this.id = id;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;

                Key key = (Key) o;
                return clazz == key.clazz &&
                        Objects.equals(id, key.id);
            }

            @Override
            public int hashCode() {
                return Objects.hash(clazz, id);
            }
        }

        private Map<Key, Entry<?, Integer>> map = new HashMap<>();

        /**
         * Gets the specified object from the internal cache. If not found,
         * loads it from Zookeeper and caches it.
         */
        private Object cachedGet(Class<?> clazz, Object id)
                throws SerializationException, StateAccessException {
            Key key = new Key(clazz, id);
            Entry<?, Integer> entry = map.get(key);
            if (entry == null) {
                entry = getWithVersion(clazz, id);
                map.put(key, entry);
            }

            return entry.getKey();
        }

        /**
         * Get a list of operations to update all cached objects.
         */
        private List<Op> getUpdateOps()
                throws SerializationException, StateAccessException{
            List<Op> ops = new ArrayList<>(map.size());
            for (Entry<Key, Entry<?, Integer>> entry : map.entrySet()) {
                String path = getPath(entry.getKey().clazz, entry.getKey().id);
                Object obj = entry.getValue().getKey();
                Integer version = entry.getValue().getValue();
                ops.add(updateOp(path, obj, version));
            }
            return ops;
        }

        /**
         * Adds a backreference from the instance of thatClass whose ID is
         * thatId (thatObj) to thisId, using field thatField. Adds thatObj to
         * the cache.
         *
         * If thatField is null, thatObj will be loaded and cached, but no
         * backreference will be added.
         */
        private void addBackreference(FieldBinding bdg,
                                      Object thisId, Object thatId)
                throws SerializationException, StateAccessException {

            Object thatObj = cachedGet(bdg.thatClass, thatId);

            // If thatObj has no field for a backref, then we don't actually
            // need to add one. We did need to load it so that we can increment
            // its version later to guard against concurrent updates.
            if (bdg.thatField == null)
                return;

            if (isList(bdg.thatField)) {
                addToList(thatObj, bdg.thatField, thisId);
            } else {
                Object curFieldVal = getValue(thatObj, bdg.thatField);
                if (curFieldVal == null) {
                    setValue(thatObj, bdg.thatField, thisId);
                } else {
                    // TODO: Better exception.
                    throw new RuntimeException(String.format(
                            "%s already has a value for field %s.",
                            thatObj, bdg.thatField.getName()));
                }
            }
        }

        /**
         * Removes a backreference from the instance of thatClass whose ID
         * is thatId (thatObj) to thisId, using field thatField. Adds
         * thatObj to the cache.
         *
         * ThatObj is assumed to exist.
         */
        private void clearBackReference(FieldBinding bdg,
                                        Object thisId, Object thatId)
                throws SerializationException, StateAccessException {
            Object thatObj = cachedGet(bdg.thatClass, thatId);
            assert(thatObj != null);

            if (isList(bdg.thatField)) {
                removeFromList(thatObj, bdg.thatField, thisId);
            } else {
                Object curFieldVal = getValue(thatObj, bdg.thatField);
                if (!Objects.equals(curFieldVal, thisId)) {
                    throw new ConcurrentModificationException(String.format(
                            "Expected field %s of %s to be %s, but was not.",
                            bdg.thatField.getName(), thatObj, thisId));
                }
                setValue(thatObj, bdg.thatField, null);
            }
        }
    }

    /**
     * Declares a binding which create, update, and delete operations will use
     * to perform referential integrity. The bindings are bidirectional, and
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
     *      CASCADE: Delete the bound rightClass instance.
     *
     *      CLEAR: Remove the bound rightClass instance's reference to this
     *      leftClass instance. If rightFieldName is a list, then this will
     *      remove the leftClass instance's id from the rightClass instance's
     *      list field named by rightField. Otherwise it will set that field
     *      to null.
     *
     *      ERROR: Do not allow an instance of leftClass to be deleted unless
     *      the value of the field named by leftFieldName is either null or
     *      an empty list.
     *
     * @param rightClass See leftClass.
     * @param rightFieldName See leftFieldName.
     * @param onDeleteRight See onDeleteLeft.
     */
    public void declareBinding(Class<?> leftClass, String leftFieldName,
                               DeleteAction onDeleteLeft,
                               Class<?> rightClass, String rightFieldName,
                               DeleteAction onDeleteRight) {
        // TODO: Check type compatibility.
        Field leftField = getField(leftClass, leftFieldName);
        Field rightField = getField(rightClass, rightFieldName);
        if (leftField != null)
            allBindings.put(leftClass, new FieldBinding(leftField,
                    rightClass, rightField, onDeleteLeft));
        if (rightField != null && leftClass != rightClass)
            allBindings.put(rightClass, new FieldBinding(rightField,
                    leftClass, leftField, onDeleteRight));
    }

    /**
     * For testing. There should be no need to do this in production.
     */
    void clearBindings() {
        allBindings.clear();
    }

    /**
     * Persists the specified object to Zookeeper. The object must
     * have a field named "id".
     */
    public void create(Object o)
            throws SerializationException, StateAccessException {

        // TODO: Cache id field for each class?
        // TODO: Initialize ID if null?
        Class<?> objClass = o.getClass();
        Field idField = getField(objClass, "id");
        Object thisId = getValue(o, idField);

        ReferencedObjManager referencedObjManager = new ReferencedObjManager();
        for (FieldBinding bdg : allBindings.get(objClass)) {
            for(Object thatId : getValueAsList(o, bdg.thisField)) {
                referencedObjManager.addBackreference(bdg, thisId, thatId);
            }
        }

        List<Op> ops = new ArrayList<>();
        ops.add(createOp(o, thisId));
        ops.addAll(referencedObjManager.getUpdateOps());
        zk.multi(ops);
    }

    public void delete(Class<?> clazz, Object id)
            throws SerializationException, StateAccessException {

        ReferencedObjManager referencedObjManager = new ReferencedObjManager();
        List<Object> idsToIgnore = new ArrayList<>();
        List<Op> deleteOps =
                prepareDelete(clazz, id, idsToIgnore, referencedObjManager);

        // Execute updates before deletes, so we don't try to update a
        // deleted node.
        List<Op> ops = new ArrayList<>();
        ops.addAll(referencedObjManager.getUpdateOps());
        ops.addAll(deleteOps);
        zk.multi(ops);
    }

    private List<Op> prepareDelete(Class<?> clazz, Object id,
                                   List<Object> idsToIgnore,
                                   ReferencedObjManager referencedObjManager)
                throws SerializationException, StateAccessException {

        // We're deleting this object, so we can ignore references to it.
        idsToIgnore.add(id);

        Entry<?, Integer> entry = getWithVersion(clazz, id);
        Object thisObj = entry.getKey();
        Integer thisVersion = entry.getValue();

        List<Op> deleteOps = new ArrayList<>();
        deleteOps.add(Op.delete(getPath(clazz, id), thisVersion));

        for (FieldBinding bdg : allBindings.get(clazz)) {
            // Nothing to do if thatClass has no backref field.
            if (bdg.thatField == null)
                continue;

            // Get ID(s) from thisField. Nothing to do if there are none.
            List<?> thoseIds = getValueAsList(thisObj, bdg.thisField);
            if (thoseIds.isEmpty())
                continue;

            for (Object thatId : new HashSet<>(thoseIds)) {
                if (idsToIgnore.contains(thatId))
                    continue;

                if (bdg.onDeleteThis == DeleteAction.ERROR) {
                    // TODO: Better exception.
                    throw new RuntimeException("Cannot delete " + thisObj +
                            " because it is still needed by " +
                            get(bdg.thatClass, thatId));
                } else if (bdg.onDeleteThis == DeleteAction.CLEAR) {
                    referencedObjManager.clearBackReference(bdg, id, thatId);
                } else { // CASCADE
                    // This will break in the case where A has bindings with
                    // cascading delete to B and C, and B has a binding to
                    // C with ERROR semantics. This would be complicated to
                    // fix and probably isn't needed, so I'm leaving it as is.
                    deleteOps.addAll(
                            prepareDelete(bdg.thatClass, thatId, idsToIgnore,
                                          referencedObjManager));
                }
            }
        }

        return deleteOps;
    }

    private <T> Entry<T, Integer> getWithVersion(Class<T> clazz, Object id)
            throws SerializationException, StateAccessException {
        Entry<byte[], Integer> entry =
                zk.getWithVersion(getPath(clazz, id), null);
        return new ImmutablePair<>(
                serializer.deserialize(entry.getKey(), clazz),
                entry.getValue());
    }

    /**
     * Gets the specified instance of the specified class.
     */
    public <T> T get(Class<T> clazz, Object id)
            throws SerializationException, StateAccessException {
        byte[] data = zk.get(getPath(clazz, id));
        return serializer.deserialize(data, clazz);
    }

    /**
     * Gets all instances of the specified class that have been created
     * with create() and not yet deleted.
     */
    public <T> List<T> getAll(Class<T> clazz)
            throws SerializationException {
        Set<String> ids;
        try {
            ids = zk.getChildren(getPath(clazz));
        } catch (StateAccessException ex) {
            // We should make sure top-level class nodes exist during startup.
            throw new RuntimeException(ex);
        }

        List<T> objs = new ArrayList<>(ids.size());
        for (String id : ids) {
            try {
                objs.add(get(clazz, id));
            } catch (StateAccessException ex) {
                // Must have been deleted since we fetched the ID list.
            }
        }
        return objs;
    }

    public boolean exists(Class<?> clazz, Object id)
            throws StateAccessException {
        return zk.exists(getPath(clazz, id));
    }

    public String getPath(Class<?> clazz) {
        return basePath + "/" + clazz.getSimpleName();
    }

    public String getPath(Class<?> clazz, Object id) {
        return getPath(clazz) + "/" + id;
    }

    private Op createOp(Object o, Object id) throws SerializationException {
        return zk.getPersistentCreateOp(getPath(o.getClass(), id),
                                        serializer.serialize(o));
    }

    private Op updateOp(String path, Object o, int version)
            throws SerializationException {
        return Op.setData(path, serializer.serialize(o), version);
    }

    /**
     * Converts Class.getDeclaredField()'s NoSuchFieldException to a
     * RuntimeException.
     */
    private Field getField(Class<?> clazz, String name) {
        if (name == null)
            return null;

        try {
            Field f = clazz.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Converts Field.get()'s IllegalAccessException to a RuntimeException.
     */
    private Object getValue(Object o, Field f) {
        try {
            return f.get(o);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Gets the value of field f on object o as a list.
     *
     * @return
     *      -If the value is null, an empty list.
     *      -If the value is not a list, a list containing only the value.
     *      -If the value is a list, the value itself.
     */
    private List<?> getValueAsList(Object o, Field f) {
        Object val = getValue(o, f);
        if (val == null)
            return Collections.emptyList();

        return (val instanceof List<?>) ? (List<?>)val : Arrays.asList(val);
    }

    /**
     * Convert Field.set()'s IllegalAccessException to a RuntimeException.
     */
    private void setValue(Object o, Field f, Object val) {
        try {
            f.set(o, val);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }

    private boolean isList(Field f) {
        return List.class.isAssignableFrom(f.getType());
    }

    /**
     * Adds val to o's field f. F's type must be a subclass of List.
     */
    private void addToList(Object o, Field f, Object val) {
        List<Object> list = (List<Object>)getValue(o, f);
        if (list == null) {
            list = new ArrayList<>();
            setValue(o, f, list);
        }
        list.add(val);
    }

    /**
     * Removes a value from o's field f. F's type must be a subclass of List.
     */
    private void removeFromList(Object o, Field f, Object val) {
        List<?> list = (List<?>)getValue(o, f);
        if (!list.remove(val)) {
            throw new ConcurrentModificationException(
                    "Expected to find "+val+" in list "+f.getName()+" of "+o+
                    ", but did not.");
        }
    }
}
