/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.updater;

import java.util.List;

import com.midokura.midolman.agent.interfaces.InterfaceDescription;

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
     * @param interfaces the list of interface data we wish to use when updating
     *                   the datastore.
     */
    void updateInterfacesData(List<InterfaceDescription> interfaces);
}
