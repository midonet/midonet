/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.commands.HostCommandGenerator;
import com.midokura.midolman.agent.state.HostDirectory;
import com.midokura.midolman.agent.state.HostZkManager;
import com.midokura.midolman.mgmt.data.dto.Host;
import com.midokura.midolman.mgmt.data.dto.HostCommand;
import com.midokura.midolman.mgmt.data.dto.Interface;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkPathManager;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com> Date: 1/31/12
 */
public class HostZkDao {

	private final static Logger log = LoggerFactory.getLogger(HostZkDao.class);

	private final HostZkManager zkDao;
	private final ZkPathManager pathBuilder;

	/**
	 * Constructor
	 *
	 * @param zkDao
	 *            ZkManager object to access ZK data.
	 * @param pathBuilder
	 *            ZkPathManager to use for ZK path access.
	 */
	public HostZkDao(HostZkManager zkDao, ZkPathManager pathBuilder) {
		this.zkDao = zkDao;
		this.pathBuilder = pathBuilder;
	}

	public Set<UUID> getHostIds() throws StateAccessException {
		String path = pathBuilder.getHostsPath();
		Set<String> ids = zkDao.getChildren(path, null);

		Set<UUID> uuids = new HashSet<UUID>();

		for (String id : ids) {
			uuids.add(UUID.fromString(id));
		}

		return uuids;
	}

	public boolean exists(UUID id) throws StateAccessException {
		return zkDao.exists(pathBuilder.getHostPath(id));
	}

	public Host get(UUID id) throws StateAccessException {

		ZkNodeEntry<UUID, HostDirectory.Metadata> metadataZkNodeEntry = zkDao
				.getHostMetadata(id);

		Host host = new Host(id);
		HostDirectory.Metadata hostMetadata = metadataZkNodeEntry.value;

		host.setName(hostMetadata.getName());
		List<String> addresses = new ArrayList<String>();
		if (hostMetadata.getAddresses() != null) {
			for (InetAddress inetAddress : hostMetadata.getAddresses()) {
				addresses.add(inetAddress.toString());
			}
		}
		host.setAddresses(addresses);
		host.setAlive(zkDao.isAlive(id));
		return host;
	}

	public Collection<UUID> getInterfaceIds(UUID hostId)
			throws StateAccessException {
		String interfacesPath = pathBuilder.getHostInterfacesPath(hostId);

		if (!zkDao.exists(interfacesPath)) {
			return Collections.emptySet();
		}

		return zkDao.getInterfaceIds(hostId);
	}

	public Interface getInterface(UUID hostId, UUID interfaceId)
			throws StateAccessException {
		ZkNodeEntry<UUID, HostDirectory.Interface> metadataZkNodeEntry = zkDao
				.getInterfaceData(hostId, interfaceId);

		HostDirectory.Interface interfaceData = metadataZkNodeEntry.value;

		return toDtoInterfaceObject(hostId, interfaceId, interfaceData);
	}

	private Interface toDtoInterfaceObject(UUID hostId, UUID interfaceId,
			HostDirectory.Interface interfaceData) {
		Interface hostInterface = new Interface();

		hostInterface.setName(interfaceData.getName());
		if (interfaceData.getMac() != null) {
			hostInterface.setMac(new MAC(interfaceData.getMac()).toString());
		}
		hostInterface.setStatus(interfaceData.getStatus());
		hostInterface.setMtu(interfaceData.getMtu());
		hostInterface.setId(interfaceId);
		hostInterface.setHostId(hostId);
		if (interfaceData.getType() != null) {
			hostInterface.setType(Interface.Type.valueOf(interfaceData
					.getType().name()));
		}
		hostInterface.setAddresses(interfaceData.getAddresses());
		hostInterface.setEndpoint(interfaceData.getEndpoint());
		hostInterface.setProperties(interfaceData.getProperties());

		return hostInterface;
	}

	private HostDirectory.Interface toHostDirectoryInterface(Interface intface) {

		HostDirectory.Interface hostInterface = new HostDirectory.Interface();

		hostInterface.setName(intface.getName());
		if (intface.getMac() != null) {
			hostInterface.setMac(MAC.fromString(intface.getMac()).getAddress());
		}
		hostInterface.setStatus(intface.getStatus());
		hostInterface.setMtu(intface.getMtu());
		hostInterface.setId(intface.getId());
		if (intface.getType() != null) {
			hostInterface.setType(HostDirectory.Interface.Type.valueOf(intface
					.getType().name()));
		}
		hostInterface.setAddresses(intface.getAddresses());
		hostInterface.setEndpoint(intface.getEndpoint());
		hostInterface.setProperties(intface.getProperties());
		return hostInterface;
	}

	public boolean existsInterface(UUID hostId, UUID interfaceId)
			throws StateAccessException {
		return zkDao.exists(pathBuilder.getHostInterfacePath(hostId,
				interfaceId));
	}

	public void delete(UUID hostId) throws StateAccessException {
		zkDao.deleteHost(hostId);
	}

	public HostCommand registerCommandForInterface(UUID hostId,
			UUID curInterfaceId, Interface newInterface)
			throws StateAccessException {

		HostCommandGenerator commandGenerator = new HostCommandGenerator();

		HostDirectory.Interface curHostInterface = null;

		if (curInterfaceId != null) {
			ZkNodeEntry<UUID, HostDirectory.Interface> curInterfacePair = zkDao
					.getInterfaceData(hostId, curInterfaceId);

			curHostInterface = curInterfacePair.value;
		}

		HostDirectory.Interface newHostInterface = toHostDirectoryInterface(newInterface);

		HostDirectory.Command command = commandGenerator.createUpdateCommand(
				curHostInterface, newHostInterface);

		Integer commandId = zkDao.createHostCommandId(hostId, command);

		HostCommand dtoCommand = new HostCommand();
		dtoCommand.setId(commandId);
		dtoCommand.setHostId(hostId);

		return dtoCommand;
	}

	public List<HostCommand> getCommands(UUID hostId)
			throws StateAccessException {

		List<Integer> commandsIds = zkDao.getCommandIds(hostId);
		List<HostCommand> commands = new ArrayList<HostCommand>();
		for (Integer commandsId : commandsIds) {

			HostCommand hostCommand = getCommand(hostId, commandsId);

			if (hostCommand != null) {
				commands.add(hostCommand);
			}
		}

		return commands;
	}

	public HostCommand getCommand(UUID hostId, Integer id)
			throws StateAccessException {

		HostCommand command = null;

		try {
			zkDao.getCommandData(hostId, id);

			command = new HostCommand();

			command.setId(id);
			command.setHostId(hostId);
		} catch (StateAccessException e) {
			log.warn("Could not read command with id {} from datastore "
					+ "(for host: {})", new Object[] { id, hostId, e });
		}

		return command;
	}

	public void deleteHostCommand(UUID hostId, Integer id)
			throws StateAccessException {
		zkDao.deleteHostCommand(hostId, id);
	}
}
