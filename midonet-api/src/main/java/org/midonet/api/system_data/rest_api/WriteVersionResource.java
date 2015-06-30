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

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;

import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.system_data.WriteVersion;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.midolman.state.StateAccessException;

/**
 * Root Resource class for Write Version Data
 */
@RequestScoped
public class WriteVersionResource extends AbstractResource {

    @Inject
    public WriteVersionResource(RestApiConfig config, UriInfo uriInfo,
                         SecurityContext context, DataClient dataClient) {
        super(config, uriInfo, context, dataClient, null);
    }

    /**
     * Handler for returning the write version info
     *
     * @return List of trace messages associated with the given trace ID
     * @throws StateAccessException
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_WRITE_VERSION_JSON,
                  MediaType.APPLICATION_JSON})
    public WriteVersion get()
        throws StateAccessException {
        org.midonet.cluster.data.WriteVersion writeVersionData =
                dataClient.writeVersionGet();
        WriteVersion writeVersion = new WriteVersion(writeVersionData);
        writeVersion.setBaseUri(getBaseUri());
        return writeVersion;
    }

    /**
     * Handler for updating the write version info.
     *
     * @param writeVersion the new write version info
     * @throws StateAccessException
     */
    @PUT
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({VendorMediaType.APPLICATION_WRITE_VERSION_JSON,
            MediaType.APPLICATION_JSON})
    public void update(WriteVersion writeVersion)
            throws StateAccessException {
        dataClient.writeVersionUpdate(writeVersion.toData());
    }
}
