/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.services.rest_api

import javax.ws.rs.core.MediaType
import javax.ws.rs.PUT
import javax.ws.rs.Path
import javax.ws.rs.Consumes
import javax.ws.rs.Produces


@Path("/echo")
class EchoHandler {
    @PUT
    @Consumes(Array(MediaType.TEXT_PLAIN))
    @Produces(Array(MediaType.TEXT_PLAIN))
    def put(value: String) = value
}
