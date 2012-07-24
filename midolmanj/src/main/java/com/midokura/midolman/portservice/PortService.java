/**
 * PortService.java - An interface for port service classes.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.portservice;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.layer3.ServiceFlowController;
import com.midokura.midolman.openvswitch.OpenvSwitchException;
import com.midokura.packets.MAC;
import com.midokura.midolman.state.StateAccessException;

public interface PortService {

    public void clear();

    public void setController(ServiceFlowController controller);

    public Set<String> getPorts(UUID portId) throws StateAccessException;

    public void addPort(long datapathId, UUID portId, MAC mac)
        throws StateAccessException;

    public void addPort(long datapathId, UUID portId)
        throws StateAccessException;

    public UUID getRemotePort(String portName)
        throws OpenvSwitchException.NotFoundException;

    public void configurePort(UUID portId, String portName)
        throws IOException, StateAccessException, InterruptedException;

    public void configurePort(UUID portId)
        throws IOException, StateAccessException, InterruptedException;

    public void delPort(UUID portId);

    public void start(long datapathId, short localPortNum, short remotePortNum)
        throws StateAccessException, IOException;

    public void start(UUID serviceId) throws StateAccessException, IOException;

    public void stop(UUID serviceId);
}
