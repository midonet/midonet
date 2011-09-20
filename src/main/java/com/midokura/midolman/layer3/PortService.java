package com.midokura.midolman.layer3;

import com.midokura.midolman.L3DevicePort;
import com.midokura.midolman.state.ZkStateSerializationException;

import java.util.Set;
import java.util.UUID;
import java.io.IOException;

import org.apache.zookeeper.KeeperException;

public interface PortService {

    public void setController(NetworkController controller);

    public Set<String> getPorts(L3DevicePort port) throws KeeperException,
        InterruptedException, ZkStateSerializationException;

    public void addPort(long datapathId, L3DevicePort port)
        throws KeeperException, InterruptedException,
        ZkStateSerializationException;

    public UUID getRemotePort(long datapathId, short portNum,
                              String portName);

    public void configurePort(long datapathId, UUID portId, String portName)
        throws KeeperException, InterruptedException,
        ZkStateSerializationException, IOException;

    public void start(short localPortNum, L3DevicePort remotePort) throws
        KeeperException, InterruptedException, ZkStateSerializationException,
        IOException;
}
