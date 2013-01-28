/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.host.updater;

import java.util.UUID;

import com.midokura.midolman.host.interfaces.InterfaceDescription;
import com.midokura.midolman.host.state.HostDirectory;

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
                              InterfaceDescription ... interfaces);
}
