/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import com.google.inject.Inject;
import com.midokura.midolman.host.commands.HostCommandGenerator;
import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.mgmt.data.dao.HostDao;
import com.midokura.midolman.mgmt.data.dto.Host;
import com.midokura.midolman.mgmt.data.dto.HostCommand;
import com.midokura.midolman.mgmt.data.dto.HostInterfacePortMap;
import com.midokura.midolman.mgmt.data.dto.Interface;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.packets.MAC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.*;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com> Date: 1/31/12
 */
public class HostDaoImpl implements HostDao {

    private final static Logger log = LoggerFactory
            .getLogger(HostDaoImpl.class);

    private final HostZkManager zkDao;

    @Inject
    public HostDaoImpl(HostZkManager zkDao) {
        this.zkDao = zkDao;
    }

    @Override
    public void delete(UUID id) throws StateAccessException {
        zkDao.deleteHost(id);
    }

    @Override
    public UUID create(Host obj) throws StateAccessException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Host get(UUID id) throws StateAccessException {
        Host host = null;

        if (zkDao.hostExists(id)) {

            HostDirectory.Metadata hostMetadata = zkDao.getHostMetadata(id);

            host = new Host(id);
            host.setName(hostMetadata.getName());
            List<String> addresses = new ArrayList<String>();
            if (hostMetadata.getAddresses() != null) {
                for (InetAddress inetAddress : hostMetadata.getAddresses()) {
                    addresses.add(inetAddress.toString());
                }
            }
            host.setAddresses(addresses);
            host.setAlive(zkDao.isAlive(id));
        }

        return host;
    }

    @Override
    public void update(Host obj) throws StateAccessException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Host> findAll() throws StateAccessException {
        Collection<UUID> ids = zkDao.getHostIds();

        List<Host> hosts = new ArrayList<Host>();

        for (UUID id : ids) {
            try {
                Host host = get(id);
                if (host != null) {
                    hosts.add(host);
                }
            } catch (StateAccessException e) {
                log.warn(
                        "Tried to read the information of a host that vanished "
                                + "or become corrupted: {}", id, e);
            }
        }

        return hosts;
    }

    @Override
    public List<Interface> listInterfaces(UUID hostId)
            throws StateAccessException {
        List<Interface> interfaces = new ArrayList<Interface>();

        Collection<String> interfaceNames = zkDao.getInterfaces(hostId);
        for (String interfaceName : interfaceNames) {
            try {
                Interface anInterface = getInterface(hostId, interfaceName);
                if (anInterface != null) {
                    interfaces.add(anInterface);
                }
            } catch (StateAccessException e) {
                log.warn(
                        "An interface description went missing in action while "
                                + "we were looking for it host: {}, interface: "
                                + "{}.",
                        new Object[] { hostId, interfaceName, e });
            }
        }

        return interfaces.size() > 0 ? interfaces : null;
    }

    @Override
    public Interface getInterface(UUID hostId, String interfaceName)
            throws StateAccessException {
        Interface anInterface = null;

        if (zkDao.existsInterface(hostId, interfaceName)) {
            HostDirectory.Interface interfaceData =
                    zkDao.getInterfaceData(hostId, interfaceName);
            anInterface = toDtoInterfaceObject(hostId, interfaceData);
        }

        return anInterface;
    }

    private Interface toDtoInterfaceObject(UUID hostId,
                                           HostDirectory.Interface
                                                   interfaceData) {
        Interface hostInterface = new Interface();

        hostInterface.setName(interfaceData.getName());
        if (interfaceData.getMac() != null) {
            hostInterface.setMac(new MAC(interfaceData.getMac()).toString());
        }
        hostInterface.setStatus(interfaceData.getStatus());
        hostInterface.setMtu(interfaceData.getMtu());
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

    @Override
    public HostCommand createCommandForInterfaceUpdate(UUID hostId,
                                                       String curInterfaceId,
                                                       Interface newInterface)
            throws StateAccessException {
        HostCommandGenerator commandGenerator = new HostCommandGenerator();

        HostDirectory.Interface curHostInterface = null;

        if (curInterfaceId != null) {
            curHostInterface = zkDao.getInterfaceData(hostId, curInterfaceId);
        }

        HostDirectory.Interface newHostInterface =
                toHostDirectoryInterface(newInterface);

        HostDirectory.Command command = commandGenerator.createUpdateCommand(
                curHostInterface, newHostInterface);

        Integer commandId = zkDao.createHostCommandId(hostId, command);

        HostCommand dtoCommand = new HostCommand();
        dtoCommand.setId(commandId);
        dtoCommand.setHostId(hostId);

        return dtoCommand;
    }

    private HostDirectory.Interface toHostDirectoryInterface(
            Interface intface) {

        HostDirectory.Interface hostInterface = new HostDirectory.Interface();

        hostInterface.setName(intface.getName());
        if (intface.getMac() != null) {
            hostInterface.setMac(MAC.fromString(intface.getMac()).getAddress());
        }
        hostInterface.setStatus(intface.getStatus());
        hostInterface.setMtu(intface.getMtu());
        if (intface.getType() != null) {
            hostInterface.setType(HostDirectory.Interface.Type.valueOf(intface
                    .getType().name()));
        }
        hostInterface.setAddresses(intface.getAddresses());
        hostInterface.setEndpoint(intface.getEndpoint());
        hostInterface.setProperties(intface.getProperties());
        return hostInterface;
    }

    @Override
    public List<HostCommand> listCommands(UUID hostId)
            throws StateAccessException {
        List<Integer> commandsIds = zkDao.getCommandIds(hostId);
        List<HostCommand> commands = new ArrayList<HostCommand>();
        for (Integer commandsId : commandsIds) {

            HostCommand hostCommand = getCommand(hostId, commandsId);

            if (hostCommand != null) {
                commands.add(hostCommand);
            }
        }

        return commands;    }

    @Override
    public HostCommand getCommand(UUID hostId, Integer id)
            throws StateAccessException {

        HostCommand command = null;

        try {
            HostDirectory.Command hostCommand =
                    zkDao.getCommandData(hostId, id);

            HostDirectory.ErrorLogItem errorLogItem =
                    zkDao.getErrorLogData(hostId, id);

            command = new HostCommand();

            command.setId(id);
            command.setHostId(hostId);
            command.setInterfaceName(hostCommand.getInterfaceName());
            command.setCommands(translateCommands(
                    hostCommand.getCommandList()));
            if ( errorLogItem != null)
                command.setLogEntries(translateErrorLog(errorLogItem));
        } catch (StateAccessException e) {
            log.warn("Could not read command with id {} from datastore "
                    + "(for host: {})", new Object[] { id, hostId, e });
            throw e;
        }

        return command;
    }

    private HostCommand.LogEntry[] translateErrorLog(
            HostDirectory.ErrorLogItem item) {

        HostCommand.LogEntry logEntry = new HostCommand.LogEntry();

        logEntry.setTimestamp(item.getTime().getTime());
        logEntry.setMessage(item.getError());

        return new HostCommand.LogEntry[] { logEntry };
    }

    private HostCommand.Command[] translateCommands(
            List<HostDirectory.Command.AtomicCommand> list)
    {
        HostCommand.Command[] commands = new HostCommand.Command[list.size()];

        int pos = 0;
        for (HostDirectory.Command.AtomicCommand atomicCommand : list) {
            HostCommand.Command newCommand = new HostCommand.Command();

            newCommand.setOperation(atomicCommand.getOpType().name());
            newCommand.setProperty(atomicCommand.getProperty().getKey());
            newCommand.setValue(atomicCommand.getValue());
            commands[pos] = newCommand;

            pos = pos + 1;
        }

        return commands;
    }

    @Override
    public void deleteCommand(UUID hostId, Integer id)
            throws StateAccessException {
        zkDao.deleteHostCommand(hostId, id);
    }

    @Override
    public void createHostInterfacePortMap(HostInterfacePortMap map)
            throws StateAccessException {

        HostDirectory.VirtualPortMapping mapping =
                new HostDirectory.VirtualPortMapping(map.getPortId(),
                        map.getInterfaceName());
        zkDao.addVirtualPortMapping(map.getHostId(), mapping);

    }

    @Override
    public void deleteHostInterfacePortMap(UUID hostId, UUID portId)
            throws StateAccessException {

        zkDao.removeVirtualPortMapping(hostId, portId);

    }

    @Override
    public List<HostInterfacePortMap> findHostInterfaceMaps(UUID hostId)
            throws StateAccessException {

        Set<HostDirectory.VirtualPortMapping> zkMaps =
                zkDao.getVirtualPortMappings(hostId);

        List<HostInterfacePortMap> maps = new ArrayList<HostInterfacePortMap>
                (zkMaps.size());

        for(HostDirectory.VirtualPortMapping zkMap : zkMaps) {
            maps.add(new HostInterfacePortMap(hostId, zkMap));
        }

        return maps;
    }

    @Override
    public void deleteInterface(UUID hostId, String interfaceId)
            throws StateAccessException {
        //
    }
}
