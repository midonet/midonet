/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.scanner;

import java.util.Collections;
import java.util.List;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.interfaces.InterfaceDescription;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;

/**
 * Default implementation for the interface scanning component.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/8/12
 */
public class DefaultInterfaceScanner implements InterfaceScanner {

    private final static Logger log =
        LoggerFactory.getLogger(DefaultInterfaceScanner.class);

    @Inject
    OpenvSwitchDatabaseConnection ovsConnection;

    @Override
    public List<InterfaceDescription> scanInterfaces() {
        log.debug("Start scanning for interface data.");
        return Collections.emptyList();
    }
}
