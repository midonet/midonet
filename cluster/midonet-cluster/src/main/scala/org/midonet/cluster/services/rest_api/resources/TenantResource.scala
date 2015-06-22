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

import java.util.UUID
import javax.ws.rs.{GET, Path, Produces}

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.auth.{AuthService, Tenant}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@RequestScoped
class TenantResource @Inject()(authService: AuthService) {

    @GET
    @Path("{id}")
    @Produces(Array(APPLICATION_TENANT_JSON))
    def get(id: UUID): Tenant = {
        authService.getTenant(id.toString)
    }

    @GET
    @Produces(Array(APPLICATION_TENANT_COLLECTION_JSON))
    def list(): java.util.List[Tenant] = {
        authService.getTenants(null) // the req is not necessary
    }

}
