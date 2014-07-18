/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
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

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.apache.zookeeper.KeeperException.BadVersionException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.data.Stat;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction;

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

    private final String basePath;

    private final JsonFactory jsonFactory;

    private final CuratorFramework client;

    public ZookeeperObjectMapper(String basePath,
                                 CuratorFramework client) {
        this.basePath = basePath;
        this.jsonFactory = new JsonFactory(new ObjectMapper());
        this.client = client;
        client.start();
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
    private class TransactionManager {

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

        private CuratorTransactionFinal transaction;
        private final Map<Key, Entry<?, Integer>> objCache = new HashMap<>();
        private final Map<Key, Integer> objsToDelete = new HashMap<>();

        private TransactionManager(CuratorTransactionFinal transaction) {
            this.transaction = transaction;
        }

        /**
         * Gets the specified object from the internal cache. If not found,
         * loads it from Zookeeper and caches it.
         */
        private Object cachedGet(Class<?> clazz, Object id)
                throws NotFoundException {
            Key key = new Key(clazz, id);
            if (objsToDelete.containsKey(key))
                return null;

            Entry<?, Integer> entry = objCache.get(key);
            if (entry == null) {
                entry = getWithVersion(clazz, id);
                objCache.put(key, entry);
            }

            return entry.getKey();
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
                throws NotFoundException, ReferenceConflictException {

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
                    throw new ReferenceConflictException(thatObj, bdg.thatField,
                            bdg.thisField.getDeclaringClass(), curFieldVal);
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
                throws NotFoundException {
            // May return null if we're doing a delete and thatObj has already
            // been flagged for delete. If so, there's nothing to do.
            Object thatObj = cachedGet(bdg.thatClass, thatId);
            if (thatObj == null)
                return;

            if (isList(bdg.thatField)) {
                removeFromList(thatObj, bdg.thatField, thisId);
            } else {
                Object curFieldVal = getValue(thatObj, bdg.thatField);
                if (!Objects.equals(curFieldVal, thisId)) {
                    // Zookeeper doesn't support atomic reads of multiple
                    // nodes, so assume that this is caused by a concurrent
                    // modification.
                    throw new ConcurrentModificationException(String.format(
                            "Expected field %s of %s to be %s, but was not.",
                            bdg.thatField.getName(), thatObj, thisId));
                }
                setValue(thatObj, bdg.thatField, null);
            }
        }

        private void prepareDelete(Class<?> clazz, Object id)
            throws NotFoundException, ObjectReferencedException {

            Entry<?, Integer> entry = getWithVersion(clazz, id);
            Object thisObj = entry.getKey();
            Integer thisVersion = entry.getValue();

            objsToDelete.put(new Key(clazz, id), thisVersion);

            for (FieldBinding bdg : allBindings.get(clazz)) {
                // Nothing to do if thatClass has no backref field.
                if (bdg.thatField == null)
                    continue;

                // Get ID(s) from thisField. Nothing to do if there are none.
                List<?> thoseIds = getValueAsList(thisObj, bdg.thisField);
                if (thoseIds.isEmpty())
                    continue;

                for (Object thatId : new HashSet<>(thoseIds)) {
                    if (objsToDelete.containsKey(
                        new Key(bdg.thatClass, thatId)))
                        continue;

                    if (bdg.onDeleteThis == DeleteAction.ERROR) {
                        throw new ObjectReferencedException(
                            thisObj, bdg.thatClass, thatId);
                    } else if (bdg.onDeleteThis == DeleteAction.CLEAR) {
                        clearBackReference(bdg, id, thatId);
                    } else { // CASCADE
                        // Breaks if A has bindings with cascading delete to B
                        // and C, and B has a binding to C with ERROR semantics.
                        // This would be complicated to fix and probably isn't
                        // needed, so I'm leaving it as is.
                        prepareDelete(bdg.thatClass, thatId);
                    }
                }
            }
        }

        private void commit() throws Exception {
            CuratorTransactionFinal ctf = transaction;

            for (Entry<Key, Entry<?, Integer>> entry : objCache.entrySet()) {
                String path = getPath(entry.getKey().clazz, entry.getKey().id);
                Object obj = entry.getValue().getKey();
                Integer version = entry.getValue().getValue();
                ctf = ctf.setData().withVersion(version)
                    .forPath(path, serialize(obj)).and();
            }

            for (Entry<Key, Integer> entry : objsToDelete.entrySet()) {
                Key key = entry.getKey();
                String path = getPath(key.clazz, key.id);
                Integer version = entry.getValue();
                ctf = ctf.delete().withVersion(version).forPath(path).and();
            }

            ctf.commit();
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
        assert(leftFieldName != null || rightFieldName != null);

        Field leftIdField = getField(leftClass, "id");
        Field rightIdField = getField(rightClass, "id");
        Field leftRefField = getField(leftClass, leftFieldName);
        Field rightRefField = getField(rightClass, rightFieldName);

        checkTypeCompatibilityForBinding(leftIdField, rightRefField);
        checkTypeCompatibilityForBinding(rightIdField, leftRefField);

        if (leftRefField != null)
            allBindings.put(leftClass, new FieldBinding(leftRefField,
                    rightClass, rightRefField, onDeleteLeft));
        if (rightRefField != null && leftClass != rightClass)
            allBindings.put(rightClass, new FieldBinding(rightRefField,
                    leftClass, leftRefField, onDeleteRight));
    }

    private String getQualifiedName(Field f) {
        return f.getDeclaringClass().getSimpleName() + '.' + f.getName();
    }

    private void checkTypeCompatibilityForBinding(Field id, Field ref) {
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
     * For testing. There should be no need to do this in production.
     */
    void clearBindings() {
        allBindings.clear();
    }

    /**
     * Persists the specified object to Zookeeper. The object must
     * have a field named "id".
     */
    public void create(Object o) throws NotFoundException,
            ObjectExistsException, ReferenceConflictException {

        // TODO: Cache id field for each class?
        // TODO: Initialize ID if null?
        Class<?> thisClass = o.getClass();
        Field idField = getField(thisClass, "id");
        Object thisId = getValue(o, idField);

        TransactionManager transactionManager =
            transactionManagerForCreate(o, thisId);
        for (FieldBinding bdg : allBindings.get(thisClass)) {
            for (Object thatId : getValueAsList(o, bdg.thisField)) {
                transactionManager.addBackreference(bdg, thisId, thatId);
            }
        }

        try {
            transactionManager.commit();
        } catch (NodeExistsException ex) {
            throw new ObjectExistsException(thisClass, thisId);
        } catch (BadVersionException | NoNodeException ex) {
            // NoStatePathException is assumed to be due to concurrent delete
            // operation because we already sucessfully fetched any objects
            // that are being updated.
            throw new ConcurrentModificationException(ex);
        } catch (ReferenceConflictException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new InternalObjectMapperException(ex);
        }
    }

    private TransactionManager transactionManagerForCreate(Object o,
                                                           Object id) {
        try {
            return new TransactionManager(
                client.inTransaction().create()
                    .forPath(getPath(o.getClass(), id), serialize(o)).and());
        } catch (Exception ex) {
            throw new InternalObjectMapperException(ex);
        }
    }

    /**
     * Updates the specified object in Zookeeper.
     */
    public void update(Object newThisObj)
            throws NotFoundException, ReferenceConflictException {

        Class<?> thisClass = newThisObj.getClass();
        Field idField = getField(thisClass, "id");
        Object thisId = getValue(newThisObj, idField);

        Entry<?, Integer> e = getWithVersion(thisClass, thisId);
        Object oldThisObj = e.getKey();
        Integer thisVersion = e.getValue();

        TransactionManager transactionManager =
            transactionManagerForUpdate(newThisObj, thisId, thisVersion);
        for (FieldBinding bdg : allBindings.get(thisClass)) {
            List<Object> oldThoseIds =
                getValueAsList(oldThisObj, bdg.thisField);
            List<Object> newThoseIds =
                getValueAsList(newThisObj, bdg.thisField);

            List<?> removedIds = ListUtils.subtract(oldThoseIds, newThoseIds);
            for (Object thatId : removedIds) {
                transactionManager.clearBackReference(bdg, thisId, thatId);
            }

            List<?> addedIds = ListUtils.subtract(newThoseIds, oldThoseIds);
            for (Object thatId : addedIds) {
                transactionManager.addBackreference(bdg, thisId, thatId);
            }
        }

        try {
            transactionManager.commit();
        } catch (BadVersionException | NoNodeException ex) {
            // NoStatePathException is assumed to be due to concurrent delete
            // operation because we already sucessfully fetched any objects
            // that are being updated.
            throw new ConcurrentModificationException(ex);
        } catch (Exception ex) {
            throw new InternalObjectMapperException(ex);
        }
    }

    private TransactionManager transactionManagerForUpdate(Object o,
                                                           Object id,
                                                           int version) {
        try {
            return new TransactionManager(
                client.inTransaction().setData().withVersion(version)
                    .forPath(getPath(o.getClass(), id), serialize(o)).and());
        } catch (Exception ex) {
            throw new InternalObjectMapperException(ex);
        }
    }

    /**
     * Deletes the specified object from Zookeeper.
     */
    public void delete(Class<?> clazz, Object id)
            throws NotFoundException, ObjectReferencedException {

        TransactionManager transactionManager =
            new TransactionManager((CuratorTransactionFinal)client.inTransaction());
        transactionManager.prepareDelete(clazz, id);

        try {
            transactionManager.commit();
        } catch (BadVersionException | NoNodeException ex) {
            // NoStatePathException is assumed to be due to concurrent delete
            // operation because we already sucessfully fetched any objects
            // that are being updated.
            throw new ConcurrentModificationException(ex);
        } catch (Exception ex) {
            throw new InternalObjectMapperException(ex);
        }
    }

    private <T> Entry<T, Integer> getWithVersion(Class<T> clazz, Object id)
            throws NotFoundException {
        byte[] data;
        Stat stat = new Stat();
        try {
            String path = getPath(clazz, id);
            data = client.getData().storingStatIn(stat).forPath(path);
        } catch (NoNodeException ex) {
            throw new NotFoundException(clazz, id, ex);
        } catch (Exception ex) {
            throw new InternalObjectMapperException(ex);
        }

        return new ImmutablePair<>(deserialize(data, clazz), stat.getVersion());
    }

    /**
     * Gets the specified instance of the specified class.
     */
    public <T> T get(Class<T> clazz, Object id) throws NotFoundException {
        byte[] data;
        try {
            data = client.getData().forPath(getPath(clazz, id));
        } catch (NoNodeException ex) {
            throw new NotFoundException(clazz, id, ex);
        } catch (Exception ex) {
            throw new InternalObjectMapperException(ex);
        }

        return deserialize(data, clazz);
    }

    /**
     * Gets all instances of the specified class in Zookeeper.
     */
    public <T> List<T> getAll(Class<T> clazz) {
        List<String> ids;
        try {
            ids = client.getChildren().forPath(getPath(clazz));
        } catch (Exception ex) {
            // We should make sure top-level class nodes exist during startup.
            throw new InternalObjectMapperException(
                    "Node "+getPath(clazz)+" does not exist in Zookeeper.", ex);
        }

        List<T> objs = new ArrayList<>(ids.size());
        for (String id : ids) {
            try {
                objs.add(get(clazz, id));
            } catch (NotFoundException ex) {
                // Must have been deleted since we fetched the ID list.
            }
        }
        return objs;
    }

    public boolean exists(Class<?> clazz, Object id) {
        try {
            return client.checkExists().forPath(getPath(clazz, id)) != null;
        } catch (Exception ex) {
            throw new InternalObjectMapperException(ex);
        }
    }

    private String getPath(Class<?> clazz) {
        return basePath + "/" + clazz.getSimpleName();
    }

    private String getPath(Class<?> clazz, Object id) {
        return getPath(clazz) + "/" + id;
    }

    /**
     * Converts Class.getDeclaredField()'s NoSuchFieldException to an
     * IllegalArgumentException.
     */
    private Field getField(Class<?> clazz, String name) {
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

    /**
     * Converts Field.get()'s IllegalAccessException to an
     * InternalObjectMapperException.
     */
    private Object getValue(Object o, Field f) {
        try {
            return f.get(o);
        } catch (IllegalAccessException ex) {
            throw new InternalObjectMapperException(ex);
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
    private List<Object> getValueAsList(Object o, Field f) {
        Object val = getValue(o, f);
        if (val == null)
            return Collections.emptyList();

        return (val instanceof List) ? (List<Object>)val : Arrays.asList(val);
    }

    /**
     * Convert Field.set()'s IllegalAccessException to an
     * InternalObjectMapperException.
     */
    private void setValue(Object o, Field f, Object val) {
        try {
            f.set(o, val);
        } catch (IllegalAccessException ex) {
            throw new InternalObjectMapperException(ex);
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

    // TODO: Replace with protobuf serialization.
    private byte[] serialize(Object o) {
        StringWriter writer = new StringWriter();
        try {
            JsonGenerator generator = jsonFactory.createJsonGenerator(writer);
            generator.writeObject(o);
            generator.close();
        } catch (IOException ex) {
            throw new InternalObjectMapperException(
                "Could not serialize "+o, ex);
        }
        return writer.toString().trim().getBytes();
    }

    // TODO: Replace with protobuf deserialization.
    private <T> T deserialize(byte[] json, Class<T> clazz) {
        try {
            JsonParser parser = jsonFactory.createJsonParser(json);
            T t = parser.readValueAs(clazz);
            parser.close();
            return t;
        } catch (IOException ex) {
            throw new InternalObjectMapperException(
                "Could not parse JSON: " + new String(json), ex);
        }
    }
}
