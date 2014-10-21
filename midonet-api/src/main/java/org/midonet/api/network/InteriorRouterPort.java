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
package org.midonet.api.network;

import org.midonet.cluster.Client;
import org.midonet.cluster.data.Port;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Data transfer class for interior router port.
 */
@XmlRootElement
public class InteriorRouterPort extends RouterPort implements InteriorPort {

    public InteriorRouterPort() {
        super();
    }

    public InteriorRouterPort(
            org.midonet.cluster.data.ports.RouterPort
                    portData) {
        super(portData);
    }

    @Override
    public org.midonet.cluster.data.ports.RouterPort toData() {
        org.midonet.cluster.data.ports.RouterPort data =
                new org.midonet.cluster.data.ports.RouterPort();
        super.setConfig(data);
        data.setProperty(Port.Property.v1PortType,
                Client.PortType.InteriorRouter.toString());
        return data;
    }

    @Override
    public String getType() {
        return PortType.INTERIOR_ROUTER;
    }

    @Override
    public String toString() {
        return super.toString() + ", peerId=" + peerId;
    }
}
