/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;

import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.apache.curator.utils.EnsurePath;
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

import rx.Observable;
import rx.subjects.Subject;

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction;

import static org.midonet.cluster.data.storage.FieldBinding.ID_FIELD;
import static org.midonet.Util.uncheckedCast;

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

    // Cache of subjects that publish Observables for each instance of
    // their respective classes.
    private final ConcurrentMap<Class<?>,
            Subject<? extends Observable<?>, ? extends Observable<?>>>
        classSubjects = new ConcurrentHashMap<>();

    // Cache of subjects that publish updates to individual object instances.
    // The per-class concurrent maps are created during class registration,
    // and then populated on demand as subscription requests come in.
    private final Map<Class<?>, ConcurrentMap<String, ? extends Subject<?, ?>>>
        instanceSubjects = new HashMap<>();

    private final Map<String, Class<?>> simpleNameToClass = new HashMap<>();
    private final Map<Class<?>, Field> classToIdField = new HashMap<>();
    private final Map<Class<?>, FieldDescriptor> msgClassToIdFieldDesc =
        new HashMap<>();

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

    public void declareBinding(Class<?> leftClass, String leftFieldName,
                               DeleteAction onDeleteLeft,
                               Class<?> rightClass, String rightFieldName,
                               DeleteAction onDeleteRight) {
        assert(isRegistered(leftClass));
        assert(isRegistered(rightClass));

        boolean leftIsMessage = Message.class.isAssignableFrom(leftClass);
        boolean rightIsMessage = Message.class.isAssignableFrom(rightClass);
        if (leftIsMessage != rightIsMessage) {
            throw new IllegalArgumentException(
                "Cannot bind a protobuf Message class to a POJO class");
        }

        if (leftIsMessage) {
            this.allBindings.putAll(ProtoFieldBinding.createBindings(
                leftClass, leftFieldName, onDeleteLeft,
                rightClass, rightFieldName, onDeleteRight));
        } else {
            this.allBindings.putAll(PojoFieldBinding.createBindings(
                leftClass, leftFieldName, onDeleteLeft,
                rightClass, rightFieldName, onDeleteRight));
        }
    }

    /**
     * For testing. There should be no need to do this in production.
     */
    void clearBindings() {
        allBindings.clear();
    }

    /**
     * Registers the class for use. This method is not thread-safe, and
     * initializes a variety of structures which could not easily be
     * initialized dynamically in a thread-safe manner.
     *
     * Most operations require prior registration, including declareBinding.
     * Ideally this method should be called at startup for all classes
     * intended to be stored in this instance of ZookeeperObjectManager.
     */
    void registerClass(Class<?> clazz) {
        String name = clazz.getSimpleName();
        if (simpleNameToClass.containsKey(name)) {
            throw new IllegalStateException(
                "A class with the simple name "+name+" is already registered." +
                "Registering multiple classes with the same simple name is " +
                "not supported.");
        }

        simpleNameToClass.put(clazz.getSimpleName(), clazz);

        // Cache the ID field.
        try {
            if (Message.class.isAssignableFrom(clazz)) {
                msgClassToIdFieldDesc.put(
                    clazz, ProtoFieldBinding.getMessageField(clazz, ID_FIELD));
            } else {
                classToIdField.put(clazz, clazz.getField(ID_FIELD));
            }
        } catch (NoSuchFieldException ex) {
            throw new IllegalArgumentException(
                "Cannot register class "+clazz.getName()+" because it has " +
                "no field named 'id'.");
        }

        // Create the class path in Zookeeper if needed.
        EnsurePath ensurePath = new EnsurePath(getPath(clazz));
        try {
            ensurePath.ensure(client.getZookeeperClient());
        } catch (Exception ex) {
            throw new InternalObjectMapperException(ex);
        }

        // Create the map for instance subscriptions. Do this last, since
        // we use it to check whether a class is registered.
        instanceSubjects.put(
            clazz, new ConcurrentHashMap<String, Subject<?, ?>>());
    }

    /**
     * Returns true if the class has been registered with this instance
     * of ZookeeperObjectMapper by calling registerClass().
     */
    private boolean isRegistered(Class<?> clazz) {
        return instanceSubjects.containsKey(clazz);
    }

    /**
     * Persists the specified object to Zookeeper. The object must have a field
     * named "id", and an appropriate unique ID must already be assigned to the
     * object before the call.
     */
    public void create(Object o) throws NotFoundException,
            ObjectExistsException, ReferenceConflictException {
        Class<?> thisClass = o.getClass();
        assert(isRegistered(thisClass));

        Object thisId = getObjectId(o);

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
        assert(isRegistered(thisClass));

        Object thisId = getObjectId(newThisObj);

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
        assert(isRegistered(clazz));

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
        assert(isRegistered(clazz));
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
        assert(isRegistered(clazz));
        List<String> ids;
        try {
            ids = client.getChildren().forPath(getPath(clazz));
        } catch (Exception ex) {
            // We should create top-level class nodes during registration.
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

    private <T> T deserialize(byte[] json, Class<T> clazz) {
        try {
            return Message.class.isAssignableFrom(clazz) ?
                   deserializeMessage(json, clazz) :
                   deserializePojo(json, clazz);
        } catch (IOException ex) {
            // ParseException from deserializeMessage extends IOException.
            throw new InternalObjectMapperException(
                "Could not parse JSON: " + new String(json), ex);
        }
    }

    private <T> T deserializeMessage(byte[] json, Class<T> clazz)
            throws TextFormat.ParseException {
        Message.Builder builder;
        try {
            builder =
                (Message.Builder)clazz.getMethod("newBuilder").invoke(null);
        } catch (Exception ex) {
            throw new InternalObjectMapperException(
                "Could not create message builder for class "+clazz+".", ex);
        }

        TextFormat.merge(new String(json), builder);
        return uncheckedCast(builder.build());
    }

    private <T> T deserializePojo(byte[] json, Class<T> clazz)
            throws IOException {
        JsonParser parser = jsonFactory.createJsonParser(json);
        T deserialized = uncheckedCast(parser.readValueAs(clazz));
        parser.close();
        return deserialized;
    }

    /* Looks up the ID value of the given object by calling its getter. The
     * protocol buffer doesn't create a member field as the same name as defined
     * in its definition, therefore instead we call its getter.
     */
    private Object getObjectId(Object o) {
        Class<?> clazz = o.getClass();
        try {
            return (o instanceof Message) ?
                   ((Message)o).getField(msgClassToIdFieldDesc.get(clazz)) :
                   classToIdField.get(clazz).get(o);
        } catch (Exception ex) {
            throw new InternalObjectMapperException(
                "Couldn't get ID from object " + o, ex);
        }
    }
}
