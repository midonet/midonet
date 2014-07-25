/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;

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
         * Update the cached object with the specified object.
         */
        private void updateCache(Class<?> clazz, Object id, Object value) {
            Key key = new Key(clazz, id);
            if (objsToDelete.containsKey(key)) return;

            Entry<?, Integer> entry = objCache.get(key);
            if (entry != null) {
                ImmutablePair<?, Integer> updatedEntry =
                        new ImmutablePair<>(value, entry.getValue());
                objCache.put(key, updatedEntry);
            }
        }

        /**
         * Adds a backreference from the instance of thatClass whose ID is
         * thatId (thatObj) to thisId, using field thatField. Adds an
         * updated thatObj with back references added to the cache.
         *
         * If thatField is null, thatObj will be loaded and cached, but no
         * backreference will be added.
         */
        private void addBackreference(FieldBinding bdg,
                                      Object thisId, Object thatId)
                throws NotFoundException, ReferenceConflictException {

            Object thatObj = cachedGet(bdg.getReferencedClass(), thatId);

            // If thatObj has no field for a backref, then we don't actually
            // need to add one. We did need to load it so that we can increment
            // its version later to guard against concurrent updates.
            Object updatedThatObj = bdg.addBackReference(thatObj, thisId);
            updateCache(bdg.getReferencedClass(), thatId, updatedThatObj);
        }

        /**
         * Removes a backreference from the instance of thatClass whose ID
         * is thatId (thatObj) to thisId, using field thatField. Adds an
         * updated thatObj with back references removed to the cache.
         *
         * ThatObj is assumed to exist.
         */
        private void clearBackReference(FieldBinding bdg,
                                        Object thisId, Object thatId)
                throws NotFoundException {
            // May return null if we're doing a delete and thatObj has already
            // been flagged for delete. If so, there's nothing to do.
            Object thatObj = cachedGet(bdg.getReferencedClass(), thatId);
            if (thatObj == null) return;

            Object updatedThatObj = bdg.clearBackReference(thatObj, thisId);
            updateCache(bdg.getReferencedClass(), thatId, updatedThatObj);
        }

        private void prepareDelete(Class<?> clazz, Object id)
            throws NotFoundException, ObjectReferencedException {

            Entry<?, Integer> entry = getWithVersion(clazz, id);
            Object thisObj = entry.getKey();
            Integer thisVersion = entry.getValue();

            objsToDelete.put(new Key(clazz, id), thisVersion);

            for (FieldBinding bdg : allBindings.get(clazz)) {
                // Nothing to do if thatClass has no backref field.
                if (!bdg.hasBackReference()) continue;

                // Get ID(s) from thisField. Nothing to do if there are none.
                List<?> thoseIds = bdg.getFwdReferenceAsList(thisObj);
                if (thoseIds.isEmpty())
                    continue;

                for (Object thatId : new HashSet<>(thoseIds)) {
                    if (objsToDelete.containsKey(
                        new Key(bdg.getReferencedClass(), thatId)))
                        continue;

                    if (bdg.onDeleteThis() == DeleteAction.ERROR) {
                        throw new ObjectReferencedException(
                            thisObj, bdg.getReferencedClass(), thatId);
                    } else if (bdg.onDeleteThis() == DeleteAction.CLEAR) {
                        clearBackReference(bdg, id, thatId);
                    } else { // CASCADE
                        // Breaks if A has bindings with cascading delete to B
                        // and C, and B has a binding to C with ERROR semantics.
                        // This would be complicated to fix and probably isn't
                        // needed, so I'm leaving it as is.
                        prepareDelete(bdg.getReferencedClass(), thatId);
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
     * Add a new FieldBinding to class in ZooKeeperObjectMapper.
     * @param clazz A Class object for which to add a binding.
     * @param binding A FieldBinding.
     */
    public void addBinding(Class<?> clazz,  FieldBinding binding) {
         this.allBindings.put(clazz, binding);
    }

    /**
     * Add a set of new FieldBindings to ZooKeeperObjectMapper.
     * @param bindings A multi-map from a class to a set of FieldBindings for
     * the class.
     */
    public void addBindings(ListMultimap<Class<?>, FieldBinding> bindings) {
         this.allBindings.putAll(bindings);
    }

    /**
     * For testing. There should be no need to do this in production.
     */
    void clearBindings() {
        allBindings.clear();
    }

    /**
     * Persists the specified object to Zookeeper. The object must have a field
     * named "id", and an appropriate unique ID must already be assigned to the
     * object before the call.
     */
    public void create(Object o) throws NotFoundException,
            ObjectExistsException, ReferenceConflictException {
        Class<?> thisClass = o.getClass();
        Object thisId = getObjectId(thisClass, o);

        TransactionManager transactionManager =
            transactionManagerForCreate(o, thisId);
        for (FieldBinding bdg : allBindings.get(thisClass)) {
            for (Object thatId : bdg.getFwdReferenceAsList(o)) {
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
        Object thisId = getObjectId(thisClass, newThisObj);

        Entry<?, Integer> e = getWithVersion(thisClass, thisId);
        Object oldThisObj = e.getKey();
        Integer thisVersion = e.getValue();

        TransactionManager transactionManager =
            transactionManagerForUpdate(newThisObj, thisId, thisVersion);
        for (FieldBinding bdg : allBindings.get(thisClass)) {
            List<Object> oldThoseIds = bdg.getFwdReferenceAsList(oldThisObj);
            List<Object> newThoseIds = bdg.getFwdReferenceAsList(newThisObj);

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
            // operation because we already successfully fetched any objects
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
        Object uuidOrProto = id;
        if (Message.class.isAssignableFrom(clazz)) {
            uuidOrProto = ProtoFieldBinding.getIdString(id);
        }
        return getPath(clazz) + "/" + uuidOrProto;
    }

    private byte[] serialize(Object o) {
        if (o instanceof Message)
            return o.toString().getBytes();

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

    @SuppressWarnings("unchecked")
    private <T> T deserialize(byte[] json, Class<T> clazz) {
        try {
            T deserialized = null;
            if (Message.class.isAssignableFrom(clazz)) {
                Message.Builder builder = (Message.Builder)
                        clazz.getMethod("newBuilder").invoke(null);
                TextFormat.merge(new String(json), builder);
                deserialized = (T) builder.build();
            } else {
                JsonParser parser = jsonFactory.createJsonParser(json);
                deserialized = parser.readValueAs(clazz);
                parser.close();
            }
            return deserialized;
        } catch (NoSuchMethodException nsme) {
            throw new InternalObjectMapperException(
                "Cannot create a message builder.", nsme);
        } catch (IllegalAccessException iae) {
            throw new InternalObjectMapperException(
                "Cannot invoke a message builder factory.", iae);
        } catch (InvocationTargetException ite) {
            // This basically never happens.
            throw new InternalObjectMapperException(
                "The factory method is called on a wrong object.", ite);
        } catch (IOException ex) {
            throw new InternalObjectMapperException(
                "Could not parse JSON: " + new String(json), ex);
        }
    }

    /* Looks up the ID value of the given object by calling its getter. The
     * protocol buffer doesn't create a member field as the same name as defined
     * in its definition, therefore instead we call its getter.
     */
    private Object getObjectId(Class<?> clazz, Object o) {
        Object id = null;
        try {
            if (Message.class.isAssignableFrom(clazz)) {
                id = ProtoFieldBinding.getMessageId((Message) o);
            } else {
                Field f = clazz.getDeclaredField("id");
                f.setAccessible(true);
                id = f.get(o);
            }
        } catch (NoSuchFieldException nsfe) {
            // The ID field does not exist.
            log.error("No object ID field.", nsfe);
        } catch (IllegalAccessException iae) {
            // Has no access to the ID getter.
            log.error("Cannot access the object ID", iae);
        }

        return id;
    }
}
