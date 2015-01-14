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
package org.midonet.cluster.southbound.vtep;

import java.util.Collection;

import scala.util.Try;

import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observer;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.cluster.data.vtep.VtepDataClient;
import org.midonet.cluster.services.vxgw.VxLanPeer;
import org.midonet.cluster.data.vtep.VtepNotConnectedException;
import org.midonet.cluster.data.vtep.model.MacLocation;
import org.midonet.cluster.data.vtep.model.LogicalSwitch;

import static scala.collection.JavaConversions.seqAsJavaList;
import static scala.collection.JavaConversions.setAsJavaSet;

/**
 * This class exposes a hardware VTEP as a vxlan gateway peer.
 */
public class VtepBroker implements VxLanPeer {

    private final static Logger log = LoggerFactory.getLogger(VtepBroker.class);

    private final VtepDataClient vtepDataClient;
    private final Observer<MacLocation> vtepMacObserver;

    /* This is an intermediary Subject that subscribes on the Observable
     * provided by the VTEP client, and republishes all its updates. It also
     * allows us to inject updates on certain occasions (e.g.: advertiseMacs)
     */
    private Subject<MacLocation, MacLocation>
        macLocationStream = PublishSubject.create();

    @Inject
    public VtepBroker(final VtepDataClient client) {
        this.vtepDataClient = client;
        this.vtepDataClient.macLocalUpdates().subscribe(macLocationStream);
        this.vtepMacObserver = client.macRemoteUpdater();
    }

    @Override
    public void apply(MacLocation ml) {
        log.debug("Receive MAC location update {}", ml);
        if (ml == null) {
            log.warn("Ignoring null MAC-port update");
            return;
        }
        vtepMacObserver.onNext(ml);
    }

    /**
     * Triggers an advertisement of all the known Ucast_Mac_Local entries, which
     * will generate updates for each entry currently present in the table.
     *
     * This operation could make sense in the VxLanPeer interface at some point,
     * but it's not needed in the Mido peer so keeping it here for now.
     */
    public void advertiseMacs() throws VtepNotConnectedException {
        for (MacLocation ml: seqAsJavaList(vtepDataClient.currentMacLocal())) {
            macLocationStream.onNext(ml);
        }
    }

    @Override
    public Observable<MacLocation> observableUpdates() {
        return this.macLocationStream.asObservable();
    }

    /**
     * Will remove all the Midonet created logical switches from the VTEP that
     * that don't have a corresponding network id in the given list.
     */
    public void pruneUnwantedLogicalSwitches(
        Collection<java.util.UUID> wantedNetworks)
        throws VtepNotConnectedException {
        for (LogicalSwitch ls : setAsJavaSet(vtepDataClient.listLogicalSwitches())) {
            if (ls.networkId() == null || wantedNetworks.contains(ls.networkId())) {
                log.debug("Logical switch {} kept in VTEP", ls);
                continue;
            }
            Try<?> st = vtepDataClient.removeLogicalSwitch(ls.networkId());
            if (st.isSuccess()) {
                log.info("Unknown logical switch {} was removed from VTEP", ls);
            } else {
                log.warn("Cannot remove unknown logical switch {}", ls,
                         st.failed().get());
            }
        }
    }
}
