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
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.api.UriResource;

/* Class representing system state info */
@XmlRootElement
public class HostVersion extends UriResource {

    private String version;
    private UUID hostId;
    private URI host;

    /**
     * this field is irrelevant to HostVersion. Putting the JsonIgnore tag
     * will cause it to be left out of the serialized output even though
     * it is part of the parent class.
     */
    @JsonIgnore
    private URI uri;

    public HostVersion() {
        super();
    }

    public HostVersion(org.midonet.cluster.data.HostVersion hostVersionData) {
        super();
        this.version = hostVersionData.getVersion();
        this.hostId = hostVersionData.getHostId();
    }

    public org.midonet.cluster.data.HostVersion toData() {
        return new org.midonet.cluster.data.HostVersion()
                .setVersion(this.version)
                .setHostId(this.hostId);
    }

    public String getVersion() {
        return this.version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public UUID getHostId() {
        return this.hostId;
    }

    public void seHostId(UUID id) {
        this.hostId = id;
    }

    public URI getHost() {
        return this.host;
    }

    public void setHost(URI host) {
        this.host = host;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return null;
    }
}
