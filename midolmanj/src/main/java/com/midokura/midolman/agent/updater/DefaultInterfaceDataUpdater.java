/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.updater;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.interfaces.InterfaceDescription;
import com.midokura.midolman.agent.state.HostDirectory;
import com.midokura.midolman.agent.state.HostZkManager;
import com.midokura.midolman.state.StateAccessException;
import static com.midokura.midolman.agent.state.HostDirectory.Interface;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/8/12
 */
public class DefaultInterfaceDataUpdater implements InterfaceDataUpdater {

    private final static Logger log =
        LoggerFactory.getLogger(DefaultInterfaceDataUpdater.class);

    @Inject
    HostZkManager hostZkManager;

    // use a local cache
    private Map<String, Interface> previousDescriptions = new HashMap<String, Interface>();

    @Override
    public synchronized void updateInterfacesData(UUID hostID,
                                                  HostDirectory.Metadata host,
                                                  InterfaceDescription... descriptions) {
        log.debug("Start uploading the interface data ({} entries).",
                  descriptions.length);

        Map<String, Interface> currentInterfacesByName = getPreviousDescriptions();

        Map<String, Interface> newInterfacesByName = new HashMap<String, Interface>();

        for (InterfaceDescription description : descriptions) {
            Interface hostInterface =
                processDescription(description,
                                   currentInterfacesByName, newInterfacesByName);

            newInterfacesByName.put(hostInterface.getName(), hostInterface);
        }

        updateDataStore(hostID, currentInterfacesByName, newInterfacesByName);
        previousDescriptions = newInterfacesByName;
    }

    private void updateDataStore(UUID hostId,
                                 Map<String, Interface> curMapByName,
                                 Map<String, Interface> newMapByName) {
        try {
            Set<UUID> interfacesToRemove = new HashSet<UUID>();

            for (Interface curHostInterface : curMapByName.values()) {
                // the interface disappeared form the new list
                if (!newMapByName.containsKey(curHostInterface.getName())) {
                    interfacesToRemove.add(curHostInterface.getId());
                }
            }

            List<Interface> updatedInterfaces = new ArrayList<Interface>();

            for (Interface newHostInterface : newMapByName.values()) {

                // first look to see if the interface is completely new
                if (!curMapByName.containsKey(newHostInterface.getName())) {
                    updatedInterfaces.add(newHostInterface);
                    continue;
                }

                // and if not completely new then if it was updated
                Interface currentHostInterface =
                    curMapByName.get(newHostInterface.getName());

                if (!currentHostInterface.equals(newHostInterface)) {
                    updatedInterfaces.add(newHostInterface);
                }
            }

            hostZkManager.updateHostInterfaces(hostId,
                                               updatedInterfaces,
                                               interfacesToRemove);
        } catch (StateAccessException e) {
            log.warn("Updating of the interface data failed: ", e);
        }
    }

    /**
     * This method will process a InterfaceDescription provided by the scanner
     * and construct a proper object suitable for storage inside the datastore.
     * <p/>
     * It also takes care of generating and maintaining the internal UUID cache.
     * <p/>
     * It might return null if the description is up-to-date for example.
     *
     * @param description       an interface description
     * @param currentInterfaces the current list of interfaces registered
     * @param newInterfaces     the new list of interfaces that we want to register
     * @return an interface object corresponding to the description passed in
     *         and with the proper uuid set.
     */
    private Interface processDescription(InterfaceDescription description,
                                         Map<String, Interface> currentInterfaces,
                                         Map<String, Interface> newInterfaces) {
        Interface hostInterface = createHostInterfaceInstance(description);

        Interface previousHostInterface = currentInterfaces.get(
            description.getName());

        Set<UUID> uuids =
            buildInterfaceUUIDSet(currentInterfaces, newInterfaces);

        if (previousHostInterface == null) {
            log.debug("New interface named: {}. Generating a new id",
                      description.getName());

            UUID uuid;
            synchronized (this) {
                do {
                    uuid = UUID.randomUUID();
                } while (uuids.contains(uuid));
            }

            hostInterface.setId(uuid);
        } else {
            hostInterface.setId(previousHostInterface.getId());
        }

        return hostInterface;
    }

    private Set<UUID> buildInterfaceUUIDSet(
        Map<String, Interface> currentByName,
        Map<String, Interface> newByName) {
        Set<UUID> uuids = new HashSet<UUID>();

        for (Interface anInterface : currentByName.values()) {
            uuids.add(anInterface.getId());
        }

        for (Interface anInterface : newByName.values()) {
            uuids.add(anInterface.getId());
        }

        return uuids;
    }

    private Interface createHostInterfaceInstance(
        InterfaceDescription description) {
        Interface hostInterface = new Interface();

        hostInterface.setName(description.getName());
        hostInterface.setMac(description.getMac());
        hostInterface.setMtu(description.getMtu());

        switch (description.getType()) {
            case PHYS:
                hostInterface.setType(Interface.Type.Physical);
                break;
            case VIRT:
                hostInterface.setType(Interface.Type.Virtual);
                break;
            case TUNN:
                hostInterface.setType(Interface.Type.Tunnel);
                break;
            case UNKNOWN:
                hostInterface.setType(Interface.Type.Unknown);
                break;
        }
        hostInterface.setProperties(new HashMap<String, String>());
        hostInterface.setAddresses(new InetAddress[0]);

        return hostInterface;
    }

    public Map<String, Interface> getPreviousDescriptions() {
        return previousDescriptions;
    }
}
