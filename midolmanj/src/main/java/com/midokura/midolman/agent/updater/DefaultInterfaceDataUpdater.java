/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.updater;

import java.util.List;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.interfaces.InterfaceDescription;
import com.midokura.midolman.agent.state.HostZkManager;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/8/12
 */
public class DefaultInterfaceDataUpdater implements InterfaceDataUpdater {

    private final static Logger log =
        LoggerFactory.getLogger(DefaultInterfaceDataUpdater.class);

    @Inject
    HostZkManager hostZkManager;

    @Override
    public void updateInterfacesData(List<InterfaceDescription> interfaces) {
        log.debug("Start uploading the interface data.");
    }
}
