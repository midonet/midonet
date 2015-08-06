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

import javax.ws.rs.core.{MediaType, NewCookie, Response}
import javax.ws.rs.{POST, Produces}

import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.auth.Token
import org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TOKEN_JSON

@RequestScoped
class LoginResource {

    @POST
    @Produces(Array(APPLICATION_TOKEN_JSON,
                    MediaType.APPLICATION_JSON))
    def login() = {
        Response.ok(new Token("999888777666", null))
            .cookie(new NewCookie("sessionId", "999888777666"))
            .build()
    }

}


