/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midolman.portservice;

import com.midokura.midolman.layer3.ServiceFlowController;
import com.midokura.packets.MAC;
import com.midokura.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * Author: Toader Mihai Claudiu <mtoader@midokura.com>
 * <p/>
 * Date: 12/6/11
 * Time: 3:50 PM
 */
public class NullPortService implements PortService {

    private static final Logger log =
        LoggerFactory.getLogger(NullPortService.class);

    @Override
    public void clear() {
        log.warn("clear() called!");
    }

    @Override
    public void setController(ServiceFlowController controller) {
        log.warn("setController() called!");
    }

    @Override
    public Set<String> getPorts(UUID portId) throws StateAccessException {
        log.warn("getPorts({}) called!", portId);
        return Collections.emptySet();
    }

    @Override
    public void addPort(long datapathId, UUID portId, MAC mac)
        throws StateAccessException {
        log.warn("addPort({}, {}, {}) called!",
                 new Object[]{datapathId, portId, mac});
    }

    @Override
    public void addPort(long datapathId, UUID portId) throws
        StateAccessException {
        log.warn("addPort({}, {}) called!", datapathId, portId);
    }

    @Override
    public UUID getRemotePort(String portName) {
        log.warn("getRemotePort({}) called!", portName);
        return null;
    }

    @Override
    public void configurePort(UUID portId, String portName)
        throws IOException, StateAccessException {
        log.warn("configurePort({}, {}) called!", portId, portName);
    }

    @Override
    public void configurePort(UUID portId)
        throws IOException, StateAccessException {
        log.warn("configurePort({}) called!", portId);
    }

    @Override
    public void delPort(UUID portId) {
        log.warn("delPort({}) called!", portId);
    }

    @Override
    public void start(long datapathId, short localPortNum, short remotePortNum)
        throws StateAccessException, IOException {
        log.warn("start({}, {}, {}) called!",
                 new Object[]{datapathId, localPortNum, remotePortNum});
    }

    @Override
    public void start(UUID serviceId)
        throws StateAccessException, IOException {
        log.warn("start({}) called!", serviceId);
    }

    @Override
    public void stop(UUID serviceId) {
        log.warn("stop({}) called!", serviceId);
    }
}
