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

package org.midonet.cluster.services.rest_api.resources

import javax.ws.rs.core.MediaType
import javax.ws.rs.{Path, GET, Produces}

import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation.ApiResource
import org.midonet.cluster.rest_api.models.SystemState
import org.midonet.cluster.services.rest_api.MidonetMediaTypes

@ApiResource(version = 1, name = "systemState")
@Path("system_state")
@RequestScoped
/** We're probably removing this in V2, kept here as it's required by MDTS */
class SystemStateResource {

    @GET
    @Produces(Array(MidonetMediaTypes.APPLICATION_SYSTEM_STATE_JSON_V2,
                    MediaType.APPLICATION_JSON))
    def get(): SystemState = {
        val st = new SystemState()
        st.state = "ACTIVE"
        st.availability = "READWRITE"
        st
    }

}
