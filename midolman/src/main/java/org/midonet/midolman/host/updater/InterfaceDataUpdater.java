/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.host.updater;

import java.util.UUID;
import java.util.List;

import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.midolman.host.state.HostDirectory;

/**
 * Any implementation of this interface will have to update the centralized
 * datastore with the list of interfaces received.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/8/12
 */
public interface InterfaceDataUpdater {

    /**
     * It will use the list of interfaces provided as a parameter and it will
     * update the datastore with it.
     *
     * @param hostID     is the current host ID.
     * @param host       is the current host metadata.
     * @param interfaces the list of interface data we wish to use when updating
     */
    void updateInterfacesData(UUID hostID, HostDirectory.Metadata host,
                              List<InterfaceDescription> interfaces);
}
