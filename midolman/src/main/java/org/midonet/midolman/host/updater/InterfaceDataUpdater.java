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

import java.util.Set;
import java.util.UUID;

import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.midolman.host.state.HostDirectory;

/**
 * Any implementation of this interface will have to update the centralized
 * datastore with the list of interfaces received.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/8/12
 */
public interface InterfaceDataUpdater {

    /**
     * It will use the list of interfaces provided as a parameter and it will
     * update the datastore with it.
     *
     * @param hostID     is the current host ID.
     * @param host       is the current host metadata.
     * @param interfaces the list of interface data we wish to use when updating
     */
    void updateInterfacesData(UUID hostID, HostDirectory.Metadata host,
                              Set<InterfaceDescription> interfaces);
}
