/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkConnectionAwareWatcher;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;

/**
 * This class watches all the child nodes of a given ZK path, assuming that
 * all the child nodes will have an UUID as its last segment. The monitor
 * exposes two rx.Observable streams of changes, one with elements created and
 * another with elements removed.
 *
 * TODO: eventually, replace with Apache Curator's PathCache
 *
 * This class is THREAD UNSAFE (because we assume a single ZK reactor thread
 * processing its watchers).
 */
public class EntityIdSetMonitor {

    private static final Logger log =
        LoggerFactory.getLogger(EntityIdSetMonitor.class);

    /* Internal cache of child IDs */
    private Set<UUID> knownIdList = new HashSet<>();

    /* Zk Manager to access the children */
    private final WatchableZkManager zkManager;

    /* Zk Connection watcher */
    private final ZkConnectionAwareWatcher zkConnWatcher;

    /* Event publication streams */
    private final Subject<UUID, UUID> creationStream = PublishSubject.create();
    private final Subject<UUID, UUID> deletionStream = PublishSubject.create();

    /**
     * The notification handler for data changes in ZK, it will delegate to the
     * EntityMonitor. We use a TypedWatcher instead of a Runnable so that we can
     * filter out specific event types.
     */
    private static class Watcher extends Directory.DefaultTypedWatcher {
        private final EntityIdSetMonitor mon;

        public Watcher(EntityIdSetMonitor mon) {
            this.mon = mon;
        }

        @Override
        public void pathChildrenUpdated(String path) {
            mon.notifyChangesAndWatch(this);
        }

        @Override
        public void run() {
            mon.getAndWatch(this);
        }
    }

    /**
     * Create a new monitor using the given zkManager and start watching its
     * childrens
     * @throws StateAccessException
     */
    @Inject
    public EntityIdSetMonitor(WatchableZkManager zkManager,
                              ZookeeperConnectionWatcher zkConnWatcher)
        throws StateAccessException {
        this.zkManager = zkManager;
        this.zkConnWatcher = zkConnWatcher;
        notifyChangesAndWatch(new Watcher(this));
    }

    /**
     * Start watching the children nodes. Note that after this is executed, the
     * 'created' Observable will emit all the current elements of the set.
     */
    private List<UUID> getAndWatch(Directory.TypedWatcher watcher) {
        try {
            return zkManager.getAndWatchUuidList(watcher);
        } catch (NoStatePathException e) {
            log.warn("Failed to access path, won't retry");
        } catch (StateAccessException e) {
            log.warn("Failed to watch entity data; will retry");
            zkConnWatcher.handleError("EntityIdSetMonitor", watcher, e);
        }
        return null;
    }

    /**
     * Process the differences between the new and old device lists.
     * Publish the ids in the corresponding streams.
     *
     * NOTE: we're here relying on ZK having single-threaded event loop to
     * guarantee that notifications emitted off the observables are delivered
     * in order.
     */
    private void notifyChangesAndWatch(Directory.TypedWatcher watcher) {
        List<UUID> idList = getAndWatch(watcher);
        log.info("NOTIFYING: {}", idList);
        if (idList == null) {
            log.warn("Null children list returned");
            return;
        }
        for (UUID id : idList) {
            if (!knownIdList.remove(id)) {
                creationStream.onNext(id);
            }
        }
        for (UUID id : knownIdList) {
            deletionStream.onNext(id);
        }
        knownIdList = new HashSet<>(idList);
    }

    /**
     * Get the immutable set of currently known ids.
     */
    public Set<UUID> getSnapshot() {
        return knownIdList;
    }

    /**
     * An rx.Observable with all the UUIDs that are added to the children, in
     * strict order of creation. The observable will stream all the children
     * as it first connects to the storage.
     */
    public Observable<UUID> created() {
        return creationStream.asObservable();
    }

    /**
     * An rx.Observable with all the UUIDs that get removed, in strict order
     * of removal.
     */
    public Observable<UUID> deleted() {
        return deletionStream.asObservable();
    }
}

