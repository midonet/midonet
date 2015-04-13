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
package org.midonet.brain.services.rest_api.models;

import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.brain.services.rest_api.annotation.Resource;
import org.midonet.brain.services.rest_api.annotation.ResourceId;
import org.midonet.packets.IPv4;

@XmlRootElement

//@Subresource(clazz = Topology.TunnelZone.class, field = "hosts")
@Resource(path = ResourceUris.HOSTS)
public class TunnelZoneHost extends UriResource {

    private UUID tunnelZoneId;
    @ResourceId
    private UUID hostId;

    @NotNull
    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    private String ipAddress;

    //@Override
    //public String getUri() {
    //    return uriFor(ResourceUris.TUNNEL_ZONES + "/" + tunnelZoneId + "/" +
    //                  ResourceUris.HOSTS + "/" + hostId).toString();
    //}

}
