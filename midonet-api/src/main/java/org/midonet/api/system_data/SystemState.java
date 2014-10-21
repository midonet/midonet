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
package org.midonet.api.system_data;

import java.net.URI;
import javax.xml.bind.annotation.XmlRootElement;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.util.version.Since;

/* Class representing system state info */
@XmlRootElement
public class SystemState extends UriResource {

    private String state;
    private String availability;
    private String writeVersion;

    public SystemState() {
        super();
    }

    public SystemState(org.midonet.cluster.data.SystemState systemState) {
        super();
        this.state = systemState.getState();
        this.availability = systemState.getAvailability();
        this.writeVersion = systemState.getWriteVersion();
    }

    public org.midonet.cluster.data.SystemState toData() {
        return new org.midonet.cluster.data.SystemState()
            .setState(this.state)
            .setAvailability(this.availability)
            .setWriteVersion(this.writeVersion);
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getAvailability() {
        return availability;
    }

    public void setAvailability(String availability) {
        this.availability = availability;
    }

    @Since("2")
    public String getWriteVersion() {
        return this.writeVersion;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getSystemState(getBaseUri());
        } else {
            return null;
        }
    }
}
