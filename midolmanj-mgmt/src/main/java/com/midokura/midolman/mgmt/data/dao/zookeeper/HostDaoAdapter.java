/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.HostDao;
import com.midokura.midolman.mgmt.data.dto.Host;
import com.midokura.midolman.mgmt.data.dto.Interface;
import com.midokura.midolman.state.StateAccessException;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 1/31/12
 */
public class HostDaoAdapter implements HostDao {

    private final static Logger log =
        LoggerFactory.getLogger(HostDaoAdapter.class);

    private final HostZkDao zkDao;

    public HostDaoAdapter(HostZkDao zkDao) {
        this.zkDao = zkDao;
    }

    @Override
    public void delete(UUID id) throws StateAccessException {
        zkDao.delete(id);
    }

    @Override
    public Host get(UUID id) throws StateAccessException {
        Host host = null;

        if (zkDao.exists(id)) {
            host = zkDao.get(id);
        }

        return host;
    }

    @Override
    public List<Host> list() throws StateAccessException {
        Collection<UUID> ids = zkDao.getHostIds();

        List<Host> hosts = new ArrayList<Host>();

        for (UUID id : ids) {
            try {
                Host host = get(id);
                if (host != null) {
                    hosts.add(host);
                }
            } catch (StateAccessException e) {
                log.warn("Tried to read the information of a host that vanished " +
                             "or become corrupted: {}", id, e);
            }
        }

        return hosts;
    }

    @Override
    public UUID createInterface(UUID hostId, Interface anInterface)
        throws StateAccessException {
        // TODO (mtoader@midokura.com) implement interface update commands
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void deleteInterface(UUID hostId, UUID interfaceId)
        throws StateAccessException {
        // TODO (mtoader@midokura.com) implement interface update commands
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public List<Interface> listInterfaces(UUID hostId)
        throws StateAccessException {
        List<Interface> interfaces = new ArrayList<Interface>();

        Collection<UUID> interfaceIds = zkDao.getInterfaceIds(hostId);
        for (UUID interfaceId : interfaceIds) {
            try {
                Interface anInterface = getInterface(hostId, interfaceId);
                if (anInterface != null) {
                    interfaces.add(anInterface);
                }
            } catch (StateAccessException e) {
                log.warn("An interface description went missing in action while " +
                             "we were looking for it host: {}, interface: {}.",
                         new Object[] {hostId, interfaceId, e});
            }
        }

        return interfaces.size() > 0 ? interfaces : null;
    }

    @Override
    public Interface getInterface(UUID hostId, UUID interfaceId)
        throws StateAccessException {
        Interface anInterface = null;

        if (zkDao.existsInterface(hostId, interfaceId)) {
            anInterface = zkDao.getInterface(hostId, interfaceId);
        }

        return anInterface;
    }
}
