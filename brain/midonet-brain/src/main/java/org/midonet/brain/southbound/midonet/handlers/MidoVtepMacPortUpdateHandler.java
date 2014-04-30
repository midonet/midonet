/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.midonet.handlers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.events.MacPortUpdate;

import com.google.inject.Inject;

/**
 * A MAC-port update handler for Mido VTEP.
 */
public class MidoVtepMacPortUpdateHandler implements MacPortUpdateHandler {
    private final static Logger log =
            LoggerFactory .getLogger(MidoVtepMacPortUpdateHandler.class);

    // These are hard-coded for now.
    static final String midoVtepName = "midoVtep";
    @Inject
    private VtepDataClient vtepDataClient = null;

    public MidoVtepMacPortUpdateHandler() {}

    public void setVtepDataClient(VtepDataClient client) {
        this.vtepDataClient = client;
    }

    /* (non-Javadoc)
     * @see org.midonet.brain.southbound.midonet.handlers.MacPortUpdateHandler#handleMacPortUpdate(org.midonet.brain.southbound.vtep.events.MacPortUpdate)
     */
    public void handleMacPortUpdate(MacPortUpdate update) {
        String macAddress = update.mac.toString();
        if (update.isAdd()) {
            vtepDataClient.addUcastMacRemote(
                    midoVtepName, macAddress, update.newVtepIp.toString());
            log.info("Added a UCAST remote MAC to the VTEP: " + update);
        } else if (update.isDelete()) {
            log.warn("Mido MAC-port deletion is not handled yet: " + update);
        } else if (update.isUpdate()) {
            log.warn("Mido MAC-port update is not handled yet: " + update);
        } else {
            // We should never end up here.
            log.warn("Illegal Mac-port update: " + update);
        }
    }
}
