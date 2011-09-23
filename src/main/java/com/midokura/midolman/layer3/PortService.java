package com.midokura.midolman.layer3;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;

import com.midokura.midolman.L3DevicePort;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;

public interface PortService {

	public void setController(NetworkController controller);

	public Set<String> getPorts(L3DevicePort port) throws StateAccessException,
			ZkStateSerializationException;

	public void addPort(long datapathId, L3DevicePort port)
			throws StateAccessException, ZkStateSerializationException, KeeperException;

	public UUID getRemotePort(long datapathId, short portNum, String portName);

	public void configurePort(long datapathId, UUID portId, String portName)
			throws IOException, StateAccessException,
			ZkStateSerializationException;

	public void start(short localPortNum, L3DevicePort remotePort)
			throws StateAccessException, ZkStateSerializationException,
			IOException;
}
