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

package org.midonet.midolman.host.sensor;

import org.midonet.midolman.host.interfaces.InterfaceDescription;

import java.io.File;
import java.util.List;
import java.util.Set;


public class SysfsInterfaceSensor implements InterfaceSensor {

    @Override
    public void updateInterfaceData(Set<InterfaceDescription> interfaces) {
        for (InterfaceDescription interfaceDescription : interfaces) {
            // Only update those interfaces who don't already have the endpoint set
            if (interfaceDescription.getEndpoint() == InterfaceDescription.Endpoint.UNKNOWN) {
                // Is this a virtual interface?
                if (isVirtual(interfaceDescription.getName())) {
                    interfaceDescription.setEndpoint(InterfaceDescription.Endpoint.UNKNOWN);
                    interfaceDescription.setType(InterfaceDescription.Type.VIRT);
                } else if (isInSys(interfaceDescription.getName())) {
                    // Devices in sys which are not virtual devices are physical
                    interfaceDescription.setEndpoint(InterfaceDescription.Endpoint.PHYSICAL);
                    interfaceDescription.setType(InterfaceDescription.Type.PHYS);
                }
            }
        }
    }

    private boolean isVirtual(String interfaceName) {
        File f = new File("/sys/devices/virtual/net/" + interfaceName);
        return (f.isDirectory());
    }

    private boolean isInSys(String interfaceName) {
        File f = new File("/sys/class/net/" + interfaceName);
        return (f.exists());
    }
}
