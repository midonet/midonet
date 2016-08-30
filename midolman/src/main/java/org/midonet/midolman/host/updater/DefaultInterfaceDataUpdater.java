/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.host.updater;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.state.StateAccessException;

import static org.midonet.midolman.host.state.HostDirectory.Interface;
import static org.midonet.midolman.host.state.HostDirectory.Metadata;

public class DefaultInterfaceDataUpdater implements InterfaceDataUpdater {

    private final static Logger log =
        LoggerFactory.getLogger(DefaultInterfaceDataUpdater.class);

    @Inject
    HostZkManager hostZkManager;

    private Map<String, Interface> cachedInterfaces = new HashMap<>();

    private long currentSessionId = -1L;

    @Override
    public synchronized void updateInterfacesData(UUID hostID, Metadata host,
            Set<InterfaceDescription> descriptions) {

        try {
            if (hostZkManager.getSessionId() != currentSessionId) {
                // Delete the current interfaces so we start over
                hostZkManager.deleteInterfaces(hostID);
                currentSessionId = hostZkManager.getSessionId();
                cachedInterfaces.clear();
            }
        } catch (StateAccessException ex) {
            log.warn("Cleanup of host interfaces after reconnecting to "
                     + "ZooKeeper failed. Continuing.", hostID, descriptions, ex);
        }

        log.trace("Start uploading the interface data ({} entries).",
                  descriptions.size());

        Map<String, Interface> newInterfacesByName = new HashMap<>();
        for (InterfaceDescription description : descriptions) {
            Interface hostInterface = createHostInterfaceInstance(description);
            newInterfacesByName.put(hostInterface.getName(), hostInterface);
        }

        updateDataStore(hostID, newInterfacesByName);
    }

    private void updateDataStore(UUID hostId,
                                 Map<String, Interface> newMapByName) {
        try {
            Set<String> obsoleteInterfaces = new HashSet<>();
            for (Interface cachedInterface : cachedInterfaces.values()) {
                // the interface disappeared from the new list
                if (!newMapByName.containsKey(cachedInterface.getName())) {
                    obsoleteInterfaces.add(cachedInterface.getName());
                }
            }

            Set<Interface> updatedInterfaces = new HashSet<>();
            Set<Interface> createdInterfaces = new HashSet<>();
            for (Interface currentInterface : newMapByName.values()) {

                Interface cachedInterface =
                    cachedInterfaces.get(currentInterface.getName());
                if (cachedInterface == null) {
                    // It's a brand new interface, create it.
                    createdInterfaces.add(currentInterface);
                } else if (!cachedInterface.equals(currentInterface)) {
                    // The interface changed, update it.
                    updatedInterfaces.add(currentInterface);
                }
            }

            log.trace("Input: " + newMapByName);
            if (!createdInterfaces.isEmpty()) {
                log.debug("Created: " + createdInterfaces);
            }
            if (!updatedInterfaces.isEmpty()) {
                log.debug("Updated: " + updatedInterfaces);
            }
            if (!obsoleteInterfaces.isEmpty()) {
                log.debug("Removed: " + obsoleteInterfaces);
            }

            hostZkManager.updateHostInterfaces(hostId,
                                               createdInterfaces,
                                               updatedInterfaces,
                                               obsoleteInterfaces);
            // Update cached interfaces if update operation was successful.
            cachedInterfaces = newMapByName;
            // We don't update the session id here just in case it changed
            // after the last cache invalidation. If we did and the session
            // changed, we wouldn't be invalidating the interfaces from a
            // previous session.
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

        int status = 0;
        if (description.isUp())
            status |= Interface.StatusType.Up.getMask();
        if (description.hasLink())
            status |= Interface.StatusType.Carrier.getMask();
        hostInterface.setStatus(status);

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
}
