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

package org.midonet.brain.services.vladimir.api

import javax.ws.rs.core.MediaType
import javax.ws.rs.{GET, Path, Produces}

import com.google.inject.servlet.RequestScoped

import org.midonet.brain.services.vladimir.MidonetMediaTypes
import org.midonet.brain.services.vladimir.models.{ResourceUris, SystemState}

@Path("system_state") // TODO: why?!?? figure out how to use the ResourceUris one
@RequestScoped
class SystemStateResource {

    // TODO: we're cheating here, it should use a real system state, but for
    //       MDTS we don't really care
    @GET
    @Produces(Array(MidonetMediaTypes.APPLICATION_SYSTEM_STATE_JSON,
                    MidonetMediaTypes.APPLICATION_SYSTEM_STATE_JSON_V2,
                    MediaType.APPLICATION_JSON))
    def get(): SystemState = {
        val st = new SystemState()
        st.state = "ACTIVE"
        st.availability = "READWRITE"
        st
    }

}
