package org.midonet.midolman.state;

import java.util.UUID;

import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.cluster.data.storage.model.ArpEntry;
import org.midonet.packets.IPv4Addr;
import org.midonet.util.eventloop.Reactor;

public class LegacyArpCacheImpl implements ArpCache, ReplicatedMap.Watcher<IPv4Addr, ArpEntry> {

    public final UUID routerId;
    ArpTable arpTable;
    Reactor reactor;
    private final Subject<ArpCacheUpdate, ArpCacheUpdate> updates =
            PublishSubject.create();

    public LegacyArpCacheImpl(ArpTable arpTable, UUID routerId, Reactor reactor) {
        this.routerId = routerId;
        this.arpTable = arpTable;
        this.reactor = reactor;
        this.arpTable.addWatcher(this);
    }

    @Override
    public UUID routerId() {
        return routerId;
    }

    @Override
    public void processChange(IPv4Addr key, ArpEntry oldV, ArpEntry newV) {
        if (oldV == null && newV == null)
            return;
        if (newV != null && oldV != null) {
            if (newV.mac() == null && oldV.mac() == null)
                return;
            if (newV.mac() != null && oldV.mac() != null &&
                    newV.mac().equals(oldV.mac())) {
                return;
            }
        }

        updates.onNext(new ArpCacheUpdate(
                key,
                oldV != null ? oldV.mac() : null,
                newV != null ? newV.mac() : null));
    }

    @Override
    public ArpEntry get(final IPv4Addr ipAddr) {
        // It's ok to do a synchronous get on the map because it only
        // queries local state (doesn't go remote like the other calls.
        return arpTable.get(ipAddr);
    }

    @Override
    public void add(final IPv4Addr ipAddr, final ArpEntry entry) {
        reactor.submit(new Runnable() {

            @Override
            public void run() {
                try {
                    arpTable.put(ipAddr, entry);
                } catch (Exception e) {
                }
            }
        });
    }

    @Override
    public void remove(final IPv4Addr ipAddr) {
        reactor.submit(new Runnable() {

            @Override
            public void run() {
                try {
                    arpTable.removeIfOwner(ipAddr);
                } catch (Exception e) {
                }
            }
        });
    }

    @Override
    public Observable<ArpCacheUpdate> observable() {
        return updates.asObservable();
    }

    @Override
    public void close() { }
}
