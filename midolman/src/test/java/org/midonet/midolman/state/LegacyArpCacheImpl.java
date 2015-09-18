package org.midonet.midolman.state;

import org.midonet.packets.IPv4Addr;
import org.midonet.util.eventloop.Reactor;
import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import java.util.UUID;

public class LegacyArpCacheImpl implements ArpCache, ReplicatedMap.Watcher<IPv4Addr, ArpCacheEntry> {

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
    public void processChange(IPv4Addr key, ArpCacheEntry oldV,
                              ArpCacheEntry newV) {
        if (oldV == null && newV == null)
            return;
        if (newV != null && oldV != null) {
            if (newV.macAddr == null && oldV.macAddr == null)
                return;
            if (newV.macAddr != null && oldV.macAddr != null &&
                    newV.macAddr.equals(oldV.macAddr)) {
                return;
            }
        }

        updates.onNext(new ArpCacheUpdate(
                key,
                oldV != null ? oldV.macAddr : null,
                newV != null ? newV.macAddr : null));
    }

    @Override
    public ArpCacheEntry get(final IPv4Addr ipAddr) {
        // It's ok to do a synchronous get on the map because it only
        // queries local state (doesn't go remote like the other calls.
        return arpTable.get(ipAddr);
    }

    @Override
    public void add(final IPv4Addr ipAddr, final ArpCacheEntry entry) {
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

    public Observable<ArpCacheUpdate> observable() {
        return updates.asObservable();
    }
}
