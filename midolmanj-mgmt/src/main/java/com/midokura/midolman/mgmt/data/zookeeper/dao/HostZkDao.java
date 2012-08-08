/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

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

import com.midokura.midolman.host.commands.HostCommandGenerator;
import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.host.state.HostDirectory.Command;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.mgmt.data.dto.Host;
import com.midokura.midolman.mgmt.data.dto.HostCommand;
import com.midokura.midolman.mgmt.data.dto.HostCommand.LogEntry;
import com.midokura.midolman.mgmt.data.dto.Interface;
import com.midokura.packets.MAC;
import com.midokura.midolman.state.StateAccessException;
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

		HostDirectory.Metadata hostMetadata = zkDao
				.getHostMetadata(id);

		Host host = new Host(id);

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
		HostDirectory.Interface interfaceData = zkDao
				.getInterfaceData(hostId, interfaceId);

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
			curHostInterface = zkDao.getInterfaceData(hostId, curInterfaceId);
		}

		HostDirectory.Interface newHostInterface = toHostDirectoryInterface(newInterface);

		Command command = commandGenerator.createUpdateCommand(
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
		    Command hostCommand  = zkDao.getCommandData(hostId, id);

            HostDirectory.ErrorLogItem errorLogItem =
                zkDao.getErrorLogData(hostId, id);

            command = new HostCommand();

			command.setId(id);
			command.setHostId(hostId);
            command.setInterfaceName(hostCommand.getInterfaceName());
            command.setCommands(translateCommands(hostCommand.getCommandList()));
            if ( errorLogItem != null)
                command.setLogEntries(translateErrorLog(errorLogItem));
		} catch (StateAccessException e) {
			log.warn("Could not read command with id {} from datastore "
					+ "(for host: {})", new Object[] { id, hostId, e });
            throw e;
		}

		return command;
	}

    private LogEntry[] translateErrorLog(HostDirectory.ErrorLogItem item) {

        LogEntry logEntry = new LogEntry();

        logEntry.setTimestamp(item.getTime().getTime());
        logEntry.setMessage(item.getError());

        return new LogEntry[] { logEntry };
    }

    private HostCommand.Command[] translateCommands(List<Command.AtomicCommand> list)
    {
        HostCommand.Command[] commands = new HostCommand.Command[list.size()];

        int pos = 0;
        for (Command.AtomicCommand atomicCommand : list) {
            HostCommand.Command newCommand = new HostCommand.Command();

            newCommand.setOperation(atomicCommand.getOpType().name());
            newCommand.setProperty(atomicCommand.getProperty().getKey());
            newCommand.setValue(atomicCommand.getValue());
            commands[pos] = newCommand;

            pos = pos + 1;
        }

        return commands;
    }

    public void deleteHostCommand(UUID hostId, Integer id)
			throws StateAccessException {
		zkDao.deleteHostCommand(hostId, id);
	}
}
