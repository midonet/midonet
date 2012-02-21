/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.updater;

import java.util.ArrayList;
import java.util.HashMap;
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

    Map<String, UUID> nameToUuidMap = new HashMap<String, UUID>();

    Map<UUID, HostDirectory.Interface> uuidToDescriptionMap =
        new HashMap<UUID, HostDirectory.Interface>();

    @Override
    public synchronized void updateInterfacesData(UUID hostID,
                                                  HostDirectory.Metadata host,
                                                  InterfaceDescription ... descriptions)
    {
        log.debug("Start uploading the interface data ({} entries).",
                  descriptions.length);

        List<HostDirectory.Interface> interfacesData =
            new ArrayList<HostDirectory.Interface>();

        for (InterfaceDescription description : descriptions) {
            Interface interfaceData =
                processDescription(description);

            if (interfaceData != null) {
                interfacesData.add(interfaceData);
            }
        }

        updateDataStore(hostID, interfacesData);
        updateIdMappings(interfacesData);
    }

    private void updateIdMappings(List<Interface> hostInterfaces) {
        nameToUuidMap.clear();
        uuidToDescriptionMap.clear();

        for (Interface hostInterface : hostInterfaces) {
            nameToUuidMap.put(hostInterface.getName(), hostInterface.getId());
            uuidToDescriptionMap.put(hostInterface.getId(), hostInterface);
        }
    }

    private void updateDataStore(UUID hostId,
                                 List<Interface> newHostInterfaces) {

        try {
            Set<UUID> obsoleteHostInterfaces =
                hostZkManager.getInterfaceIds(hostId);

            for (Interface hostInterface : newHostInterfaces) {
                obsoleteHostInterfaces.remove(hostInterface.getId());
            }

            hostZkManager.updateHostInterfaces(hostId,
                                               newHostInterfaces,
                                               obsoleteHostInterfaces);
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
     * @param description an interface description
     * @return an object suitable for storage inside the data store
     */
    private Interface processDescription(InterfaceDescription description) {
        Interface hostInterface = createHostInterfaceInstance(description);

        UUID uuid = nameToUuidMap.get(description.getName());
        if (uuid == null) {
            log.debug("New interface named: {}. Generating a new id",
                      description.getName());

            do {
                uuid = UUID.randomUUID();
            } while (uuidToDescriptionMap.containsKey(uuid));

            nameToUuidMap.put(description.getName(), uuid);
        }

        hostInterface.setId(uuid);

        Interface oldHostInterface = uuidToDescriptionMap.get(
            uuid);

        if (hostInterface.equals(oldHostInterface)) {
            return null;
        }

        uuidToDescriptionMap.put(uuid, hostInterface);

        return hostInterface;
    }

    private Interface createHostInterfaceInstance(
        InterfaceDescription description) {
        Interface hostInterface = new Interface();

        hostInterface.setName(description.getName());
        hostInterface.setMac(description.getMac());

        return hostInterface;
    }
}
