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
import java.util.List;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;

/* Class representing the Write Version of the zookeeper data */
@XmlRootElement
public class WriteVersion extends UriResource {

    private String version;

    public WriteVersion() {
        super();
    }

    public WriteVersion(org.midonet.cluster.data.WriteVersion writeVersion) {
        super();
        this.version = writeVersion.getVersion();
    }

    public org.midonet.cluster.data.WriteVersion toData() {
        return new org.midonet.cluster.data.WriteVersion()
                .setVersion(this.version);
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getWriteVersion(getBaseUri());
        } else {
            return null;
        }
    }
}
