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

package org.midonet.api.system_data.rest_api;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.common.base.Function;
import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.system_data.HostVersion;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.util.collection.ListUtil;

/**
 * Root Resource class for System State Data
 */
@RequestScoped
public class HostVersionResource extends AbstractResource {

    @Inject
    public HostVersionResource(RestApiConfig config, UriInfo uriInfo, SecurityContext context,
        DataClient dataClient) {
        super(config, uriInfo, context, dataClient, null);
    }

    /**
     * Handler for GET requests to the system state data
     *
     * @return The system state info
     * @throws StateAccessException
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_HOST_VERSION_JSON, MediaType.APPLICATION_JSON})
    public List<HostVersion> get() throws StateAccessException {
        List<org.midonet.cluster.data.HostVersion> hostVersionsData = dataClient.hostVersionsGet();
        if (hostVersionsData == null) {
            return new ArrayList<>();
        }

        return ListUtil.map(hostVersionsData,
            new Function<org.midonet.cluster.data.HostVersion, HostVersion>() {
                @Override
                public HostVersion apply(org.midonet.cluster.data.HostVersion hostVersionData) {
                    HostVersion hostVersion = new HostVersion(hostVersionData);
                    hostVersion.setHost(ResourceUriBuilder.getHost(getBaseUri(),
                        hostVersion.getHostId()));
                    return hostVersion;
                }
            });
    }
}
