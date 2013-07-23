/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.host.updater;

import com.google.inject.Inject;
import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.*;

import static org.midonet.midolman.host.state.HostDirectory.Interface;
import static org.midonet.midolman.host.state.HostDirectory.Metadata;

public class DefaultInterfaceDataUpdater implements InterfaceDataUpdater {

    private final static Logger log =
        LoggerFactory.getLogger(DefaultInterfaceDataUpdater.class);

    @Inject
    HostZkManager hostZkManager;

    // use a local cache
    private Map<String, Interface> previousDescriptions = new HashMap<String, Interface>();

    @Override
    public synchronized void updateInterfacesData(UUID hostID, Metadata host,
            List<InterfaceDescription> descriptions) {

        log.debug("Start uploading the interface data ({} entries).",
                  descriptions.size());

        Map<String, Interface> currentInterfacesByName =
                getPreviousDescriptions();

        Map<String, Interface> newInterfacesByName = new HashMap<String, Interface>();

        for (InterfaceDescription description : descriptions) {
            Interface hostInterface = createHostInterfaceInstance(description);

            newInterfacesByName.put(hostInterface.getName(), hostInterface);
        }

        updateDataStore(hostID, currentInterfacesByName, newInterfacesByName);
        previousDescriptions = newInterfacesByName;
    }

    private void updateDataStore(UUID hostId,
                                 Map<String, Interface> curMapByName,
                                 Map<String, Interface> newMapByName) {
        try {
            Set<String> interfacesToRemove = new HashSet<String>();

            for (Interface curHostInterface : curMapByName.values()) {
                // the interface disappeared form the new list
                if (!newMapByName.containsKey(curHostInterface.getName())) {
                    interfacesToRemove.add(curHostInterface.getName());
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

            log.debug("Input: " + curMapByName);
            if (updatedInterfaces.size() > 0) {
                log.info("Updated: " + updatedInterfaces);
            }
            if (interfacesToRemove.size() > 0) {
                log.info("Removed: " + interfacesToRemove);
            }

            hostZkManager.updateHostInterfaces(hostId,
                                               updatedInterfaces,
                                               interfacesToRemove);
        } catch (StateAccessException e) {
            log.warn("Updating of the interface data failed: ", e);
        }
    }

    private Interface createHostInterfaceInstance(
        InterfaceDescription description) {
        Interface hostInterface = new Interface();

        hostInterface.setName(description.getName());
        hostInterface.setMac(description.getMac());
        hostInterface.setMtu(description.getMtu());
        hostInterface.setPortType(description.getPortType());

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

        hostInterface.setEndpoint(description.getEndpoint().toString());
        hostInterface.setProperties(new HashMap<String, String>());

        List<InetAddress> addresses = description.getInetAddresses();
        hostInterface.setAddresses(
            addresses.toArray(new InetAddress[addresses.size()]));

        hostInterface.setProperties(description.getProperties());
        return hostInterface;
    }

    public Map<String, Interface> getPreviousDescriptions() {
        return previousDescriptions;
    }
}
