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

package org.midonet.client.resource;

import java.net.URI;

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoWriteVersion;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;

public class WriteVersion extends ResourceBase<WriteVersion, DtoWriteVersion> {


    public WriteVersion(WebResource resource, URI uriForCreation, DtoWriteVersion wv) {
        super(resource, uriForCreation, wv,
              MidonetMediaTypes.APPLICATION_WRITE_VERSION_JSON());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public String getVersion() {
        return principalDto.getVersion();
    }

    public WriteVersion version(String version) {
        principalDto.setVersion(version);
        return this;
    }

    @Override
    public String toString() {
        return String.format("WriteVersion{version=%s}",
                principalDto.getVersion());
    }
}
