/*
 * Copyright 2015 Midokura SARL
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
import org.midonet.client.dto.DtoSystemState;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;

public class SystemState extends ResourceBase<SystemState, DtoSystemState> {


    public SystemState(WebResource resource, URI uriForCreation, DtoSystemState ss) {
        super(resource, uriForCreation, ss,
              MidonetMediaTypes.APPLICATION_SYSTEM_STATE_JSON_V2());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public String getState() {
        return principalDto.getState();
    }

    public SystemState setState(String state) {
        principalDto.setState(state);
        return this;
    }

    public String getAvailability() {
        return principalDto.getAvailability();
    }

    public SystemState setAvailability(String availability) {
        principalDto.setAvailability(availability);
        return this;
    }

    public String getWriteVersion() {
        return principalDto.getWriteVersion();
    }

    public SystemState setWriteVersion(String writeVersion) {
        principalDto.setWriteVersion(writeVersion);
        return this;
    }

    @Override
    public String toString() {
        return String.format("SystemState{state=%s, availability=%s, "
                             + "writeVersion-%s}",
                principalDto.getState(), principalDto.getAvailability(),
                principalDto.getWriteVersion());
    }
}
