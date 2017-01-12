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

package org.midonet.client.dto;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Author: Tomoe Sugihara <tomoe@midokura.com>
 * Date: 9/5/12
 * Time: 11:58 PM
 */

@XmlRootElement
public class DtoHostInterfacePort {

    private UUID hostId;
    private UUID portId;
    private String interfaceName;
    private URI uri;

    public UUID getHostId() {
        return hostId;
    }

    public void setHostId(UUID hostId) {
        this.hostId = hostId;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
    }

    public UUID getPortId() {
        return portId;
    }

    public void setPortId(UUID portId) {
        this.portId = portId;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    @Override
    public String toString() {
        return "DtoHostInterfacePort{" +
            "hostId=" + hostId +
            ", portId=" + portId +
            ", interfaceName='" + interfaceName + '\'' +
            ", uri=" + uri +
            '}';
    }

    /**
     * General object comparison which dispatches the actual comparison.
     *
     * @param that an object to be compared with this object
     * @return     <code>true</code> if the objects match with each other;
     *             <code>false</code> otherwise
     */
    @Override
    public boolean equals(Object that) {
        return (that instanceof DtoHostInterfacePort) &&
                this.equals((DtoHostInterfacePort) that);
    }

    /**
     * Actual object comparison which compares all properties of the objects.
     *
     * @param that an object to be compared with this object
     * @return     <code>true</code> if the objects match with each other;
     *             <false>false</false> otherwise
     */
    public boolean equals(DtoHostInterfacePort that) {
        boolean equality = this.getHostId().equals(that.getHostId()) &&
                this.getPortId().equals(that.getPortId()) &&
                this.getInterfaceName().equals(that.getInterfaceName());
        URI uri = this.getUri();
        if (uri != null) {
            equality = equality && uri.equals(that.getUri());
        }
        return equality;
    }
}
