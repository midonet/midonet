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

import com.google.inject.Inject;
import org.midonet.midolman.host.interfaces.InterfaceDescription;
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
    private Map<String, Interface> previousDescriptions = new HashMap<>();

    @Override
    public synchronized void updateInterfacesData(UUID hostID, Metadata host,
            Set<InterfaceDescription> descriptions) {

        log.trace("Start uploading the interface data ({} entries).",
                  descriptions.size());

        Map<String, Interface> newInterfacesByName = new HashMap<>();

        for (InterfaceDescription description : descriptions) {
            Interface hostInterface = createHostInterfaceInstance(description);
            newInterfacesByName.put(hostInterface.getName(), hostInterface);
        }

        updateDataStore(hostID, previousDescriptions, newInterfacesByName);
        previousDescriptions = newInterfacesByName;
    }

    private void updateDataStore(UUID hostId,
                                 Map<String, Interface> curMapByName,
                                 Map<String, Interface> newMapByName) {
        try {
            Set<String> interfacesToRemove = new HashSet<>();

            for (Interface curHostInterface : curMapByName.values()) {
                // the interface disappeared form the new list
                if (!newMapByName.containsKey(curHostInterface.getName())) {
                    interfacesToRemove.add(curHostInterface.getName());
                }
            }

            List<Interface> updatedInterfaces = new ArrayList<>();

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

            log.trace("Input: " + curMapByName);
            if (!updatedInterfaces.isEmpty()) {
                log.debug("Updated: " + updatedInterfaces);
            }
            if (!interfacesToRemove.isEmpty()) {
                log.debug("Removed: " + interfacesToRemove);
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
