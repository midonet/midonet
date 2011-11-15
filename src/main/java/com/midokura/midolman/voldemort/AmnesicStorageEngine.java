/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.voldemort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import voldemort.VoldemortException;
import voldemort.annotations.concurrency.NotThreadsafe;
import voldemort.store.NoSuchCapabilityException;
import voldemort.store.StorageEngine;
import voldemort.store.StoreCapabilityType;
import voldemort.store.StoreUtils;
import voldemort.utils.ClosableIterator;
import voldemort.utils.Pair;
import voldemort.versioning.ObsoleteVersionException;
import voldemort.versioning.Occurred;
import voldemort.versioning.Version;
import voldemort.versioning.Versioned;

/**
 * An in-memory storage engine which suffers from amnesia.
 *
 * This is a storage engine where all items are considered to be temporary.
 * There is a fixed minimum lifetime that is the same for all items, before
 * which the items will remain in the store. After the minimum lifetime passes
 * after an item is added to the store, it will eventually expire, although
 * there is no guarantee that it will expire right about the time the minimum
 * lifetime is reached.
 *
 * Getting an item will refresh it, but this only works if the number of
 * required reads is greater than one, which would allow Voldemort to take
 * care of missing replicas that were expired in other servers.
 *
 * @param <K>
 *            the type for keys
 * @param <V>
 *            the type for values
 * @param <T>
 *            the type for transforms
 *
 * @author Yoo Chung
 */
public class AmnesicStorageEngine<K, V, T> implements StorageEngine<K, V, T> {

    /*
     * The approach taken here is to maintain two maps. One map is for new items
     * that are being added, while the other map maintains old items.
     * Periodically, the old map is discarded, the new item map becomes the old
     * item map, and a fresh new item map is created. This ensures that each
     * item would survive at least a fixed amount of time, while also ensuring
     * that they will die after about twice the fixed amount of time. All
     * accesses to the item maps are synchronized.
     *
     * Alternative approaches are possible, which are described in the wiki at
     * http://goo.gl/Ul4R3
     */

    /** Name of the store. */
    private final String name;

    /** Minimum lifetime before expiring items in milliseconds. */
    private long lifetime;

    /** Maps for new and old items. */
    private Map<K, List<Versioned<V>>> newMap, oldMap;

    /** Thread which regularly evicts old items. */
    private Thread evictor;

    /**
     * Constructs an amnesic store with given minimum lifetime for items.
     *
     * @param name
     *            name for the store
     * @param lifetime
     *            minimum lifetime of items in milliseconds
     */
    public AmnesicStorageEngine(String name, long lifetime) {
        this.name = name;
        this.lifetime = lifetime;
        this.newMap = new HashMap<K, List<Versioned<V>>>();
        this.oldMap = new HashMap<K, List<Versioned<V>>>();
        this.evictor = new Evictor();

        evictor.setDaemon(true);
        evictor.start();
    }

    @Override
    protected void finalize() throws Throwable {
        evictor.interrupt();
        super.finalize();
    }

    @Override
    public synchronized List<Versioned<V>> get(K key, T transforms)
            throws VoldemortException {
        StoreUtils.assertValidKey(key);

        // always check both because of potential concurrent versions
        List<Versioned<V>> result = newMap.get(key);
        List<Versioned<V>> extra = oldMap.get(key);

        // refresh items on get while including oldMap items
        if (result == null && extra != null) {
            newMap.put(key, extra);
            oldMap.remove(key);
            result = extra;
        } else if (extra != null) {
            oldMap.clear();
            result.addAll(extra);
        }

        if (result == null)
            return new ArrayList<Versioned<V>>(0);
        else
            return new ArrayList<Versioned<V>>(result);
    }

    @Override
    public Map<K, List<Versioned<V>>> getAll(Iterable<K> keys,
            Map<K, T> transforms) throws VoldemortException {
        StoreUtils.assertValidKeys(keys);
        return StoreUtils.getAll(this, keys, transforms);
    }

    @Override
    public synchronized void put(K key, Versioned<V> value, T transforms)
            throws VoldemortException {
        StoreUtils.assertValidKey(key);

        Version version = value.getVersion();

        List<Versioned<V>> newItems = newMap.get(key);
        List<Versioned<V>> oldItems = oldMap.get(key);

        if (oldItems != null) {
            removeOldVersionsForPut(oldItems, version, key);
            if (oldItems.size() == 0)
                oldMap.remove(key);
        }

        if (newItems == null) {
            List<Versioned<V>> items = new ArrayList<Versioned<V>>();
            items.add(value);
            newMap.put(key, items);
        } else {
            removeOldVersionsForPut(newItems, version, key);
            newItems.add(value);
        }

        assert get(key, null).contains(value);
    }

    /**
     * Remove versions that occurred before the given version.
     *
     * @param items
     *            set of items to remove from
     * @param version
     *            version to compare against
     * @param key
     *            key for the versioned value, needed for error reporting
     * @throws ObsoleteVersionException
     *             in case given version is obsolete
     */
    private void removeOldVersionsForPut(List<Versioned<V>> items,
            Version version, K key) throws ObsoleteVersionException {
        List<Versioned<V>> evictees = new ArrayList<Versioned<V>>(items.size());

        for (Versioned<V> item : items) {
            Occurred occurred = version.compare(item.getVersion());
            if (occurred == Occurred.BEFORE) {
                throw new ObsoleteVersionException("Obsolete version for key '"
                        + key + "': " + version);
            } else if (occurred == Occurred.AFTER) {
                evictees.add(item);
            }
        }

        items.removeAll(evictees);
    }

    @Override
    public synchronized boolean delete(K key, Version version)
            throws VoldemortException {
        StoreUtils.assertValidKey(key);

        if (version == null) {
            List<Versioned<V>> oldItems = oldMap.remove(key);
            List<Versioned<V>> newItems = newMap.remove(key);
            return oldItems != null || newItems != null;
        }

        List<Versioned<V>> oldItems = oldMap.get(key);
        List<Versioned<V>> newItems = newMap.get(key);

        assert oldItems == null || oldItems.size() > 0;
        assert newItems == null || newItems.size() > 0;

        boolean oldRemovalResult = (oldItems == null) ? false
                : removeVersionsForDelete(oldItems, version);

        boolean newRemovalResult = (newItems == null) ? false
                : removeVersionsForDelete(newItems, version);

        if (oldItems != null && oldItems.size() == 0) {
            assert oldRemovalResult;
            oldMap.remove(key);
        }

        if (newItems != null && newItems.size() == 0) {
            assert newRemovalResult;
            newMap.remove(key);
        }

        return oldRemovalResult || newRemovalResult;
    }

    /**
     * Remove versions that occurred before given version from list of items.
     *
     * @param items
     *            list of items to remove from
     * @param version
     *            version to compare against
     * @return true only if any versions were removed
     */
    private boolean removeVersionsForDelete(List<Versioned<V>> items,
            Version version) {
        boolean deleted = false;

        Iterator<Versioned<V>> iter = items.iterator();
        while (iter.hasNext()) {
            Versioned<V> item = iter.next();
            if (item.getVersion().compare(version) == Occurred.BEFORE) {
                iter.remove();
                deleted = true;
            }
        }

        return deleted;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void close() throws VoldemortException {
    }

    @Override
    public Object getCapability(StoreCapabilityType capability) {
        throw new NoSuchCapabilityException(capability, getName());
    }

    @Override
    public List<Version> getVersions(K key) {
        return StoreUtils.getVersions(get(key, null));
    }

    @Override
    public synchronized ClosableIterator<Pair<K, Versioned<V>>> entries() {
        return new EntryIterator();
    }

    @Override
    public synchronized ClosableIterator<K> keys() {
        return new KeyIterator();
    }

    @Override
    public synchronized void truncate() {
        oldMap.clear();
        newMap.clear();
    }

    @Override
    public boolean isPartitionAware() {
        return false;
    }

    /** Expire old items and reset for a new cycle. */
    private synchronized void evict() {
        oldMap = newMap;
        newMap = new HashMap<K, List<Versioned<V>>>(oldMap.size());
    }

    /** Thread which regularly flushes old items from the store. */
    protected class Evictor extends Thread {

        /** Construct an evictor thread. */
        public Evictor() {
            super("AmnesicStoreEvictor");
        }

        @Override
        public void run() {
            try {
                while (true) {
                    sleep(lifetime);
                    evict();
                }
            } catch (InterruptedException e) {
                // just finish
            }
        }

    }

    /** Iterator for enumerating all keys in the store. */
    @NotThreadsafe
    private class KeyIterator implements ClosableIterator<K> {

        /** Actual key iterator and the next after first is exhausted. */
        private Iterator<K> iter, nextIter;

        /** Construct the key iterator. */
        public KeyIterator() {
            iter = newMap.keySet().iterator();
            nextIter = oldMap.keySet().iterator();

            if (!iter.hasNext()) {
                iter = nextIter;
                nextIter = null;
            }
        }

        @Override
        public boolean hasNext() {
            return iter.hasNext();
        }

        @Override
        public K next() {
            K result = iter.next();

            if (nextIter != null && !iter.hasNext()) {
                iter = nextIter;
                nextIter = null;
            }

            return result;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            // nothing to do
        }

    }

    /** Iterator for enumerating all key and versioned value pair in store. */
    @NotThreadsafe
    private class EntryIterator implements
            ClosableIterator<Pair<K, Versioned<V>>> {

        /** Actual key iterator and the next after first is exhausted. */
        private Iterator<Map.Entry<K, List<Versioned<V>>>> iter, nextIter;

        /** The key whose values are currently being iterated. */
        private K key;

        /** The versioned values currently being iterated. */
        private Iterator<Versioned<V>> values;

        /** Construct the entry iterator. */
        public EntryIterator() {
            iter = newMap.entrySet().iterator();
            nextIter = oldMap.entrySet().iterator();
        }

        /** Return if there is another key and value list pair. */
        private boolean hasNextEntry() {
            if (iter.hasNext()) {
                return true;
            } else if (nextIter == null) {
                return false;
            } else {
                iter = nextIter;
                nextIter = null;
                return iter.hasNext();
            }
        }

        /** Return next key and value list pair. */
        private Map.Entry<K, List<Versioned<V>>> nextEntry() {
            if (iter.hasNext()) {
                return iter.next();
            } else if (nextIter != null) {
                iter = nextIter;
                nextIter = null;
            }

            return iter.next();
        }

        @Override
        public boolean hasNext() {
            assert (key == null) == (values == null);

            if (values != null && values.hasNext())
                return true;
            else
                return hasNextEntry();
        }

        @Override
        public Pair<K, Versioned<V>> next() {
            assert !(key == null ^ values == null);

            if (values == null || !values.hasNext()) {
                Map.Entry<K, List<Versioned<V>>> entry = nextEntry();
                key = entry.getKey();
                values = entry.getValue().iterator();
                assert values.hasNext();
            }

            Versioned<V> value = values.next();
            return Pair.create(key, value);
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            // nothing to do
        }

    }

}
