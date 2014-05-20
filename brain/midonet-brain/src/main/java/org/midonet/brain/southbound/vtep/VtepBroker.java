/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import com.google.inject.Inject;
import org.midonet.brain.services.vxgw.MacLocation;
import org.midonet.brain.services.vxgw.VxLanPeer;
import org.midonet.brain.services.vxgw.VxLanPeerSyncException;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.functions.Func1;

/**
 * This class exposes a hardware VTEP as a vxlan gateway peer.
 */
public class VtepBroker implements VxLanPeer {

    private final static Logger log =
        LoggerFactory.getLogger(VtepBroker.class);

    private final VtepDataClient vtepDataClient;

    @Inject
    public VtepBroker(final VtepDataClient client) {
        this.vtepDataClient = client;
    }

    @Override
    public void apply(MacLocation ml) {
        if (ml.vxlanTunnelEndpoint != null) {
            this.applyUpdate(ml);
        }
        // TODO: handle deletes, updates
    }

    private void applyUpdate(MacLocation ml) {
        log.debug("Adding a UCAST remote MAC to the VTEP: " + ml);
        Status st = vtepDataClient.addUcastMacRemote(
            ml.logicalSwitchId,
            ml.mac.toString(),
            ml.vxlanTunnelEndpoint.toString());
        if (!st.isSuccess()) {
            throw new VxLanPeerSyncException(
                String.format("VTEP replied: %s, %s",
                              st.getCode(), st.getDescription()), ml);
        }
    }

    @Override
    public Observable<MacLocation> observableUpdates() {
        return this.vtepDataClient
                   .observableLocalMacTable()
                   .map(new Func1<TableUpdates, MacLocation>() {
                       @Override
                       public MacLocation call(
                           TableUpdates tableUpdates) {
                           return toMacLocation(tableUpdates);
                       }
                   });
    }

    /**
     * Converts a TableUpdates notification from the OVSDB client to a single
     * MacLocation update that can be applied to a VxGW Peer.
     *
     * TODO: this can actually generate a set of MacLocation events.
     */
    private MacLocation toMacLocation(TableUpdates update) {
        return null;
    }
}
