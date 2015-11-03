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

import javax.servlet.http.HttpServletRequest
import javax.ws.rs.core.{Response, UriInfo}
import javax.ws.rs._

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.auth.keystone.v2_0.{KeystoneConnectionException, KeystoneInvalidFormatException, KeystoneServerException, KeystoneUnauthorizedException}
import org.midonet.cluster.auth.{AuthService, InvalidCredentialsException, Tenant => AuthTenant}
import org.midonet.cluster.rest_api._
import org.midonet.cluster.rest_api.ResponseUtils._
import org.midonet.cluster.rest_api.annotation.ApiResource
import org.midonet.cluster.rest_api.models.Tenant
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@ApiResource(version = 1, name = "tenants", template = "tenantTemplate")
@Path("tenants")
@RequestScoped
class TenantResource @Inject()(authService: AuthService,
                               requestContext: HttpServletRequest,
                               uriInfo: UriInfo) {

    @GET
    @Path("{id}")
    @Produces(Array(APPLICATION_TENANT_JSON_V2))
    def get(@PathParam("id") id: String): Tenant = {
        wrapException {
            asResource(authService.getTenant(id.toString))
        }
    }

    @GET
    @Produces(Array(APPLICATION_TENANT_COLLECTION_JSON_V2))
    def list(): java.util.List[_ <: Tenant] = {
        wrapException {
            authService.getTenants(requestContext).asScala.map(asResource).asJava
        }
    }

    @POST
    @Consumes(Array(APPLICATION_TENANT_JSON_V2))
    def create(): Response = {
        buildErrorResponse(Status.METHOD_NOT_ALLOWED.getStatusCode,
                           getMessage(TENANT_UNMODIFIABLE))
    }

    @PUT
    @Path("{id}")
    @Consumes(Array(APPLICATION_TENANT_JSON_V2))
    def update(@PathParam("id") id: String): Response = {
        buildErrorResponse(Status.METHOD_NOT_ALLOWED.getStatusCode,
                           getMessage(TENANT_UNMODIFIABLE))
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: String): Response = {
        buildErrorResponse(Status.METHOD_NOT_ALLOWED.getStatusCode,
                           getMessage(TENANT_UNMODIFIABLE))
    }

    private def wrapException[T](f: => T): T = {
        try {
            f
        } catch {
            case e: KeystoneConnectionException =>
                throw new ServiceUnavailableHttpException(
                    "Keystone identity service is not available")
            case e: KeystoneInvalidFormatException =>
                throw new BadRequestHttpException(e)
            case e: KeystoneServerException =>
                throw new ServiceUnavailableHttpException(
                    s"Keystone identity service returned an error: " +
                    s"${e.getMessage}")
            case e: KeystoneUnauthorizedException =>
                throw new UnauthorizedHttpException(
                    s"Keystone credentials are not valid")
            case e: InvalidCredentialsException =>
                throw new UnauthorizedHttpException(
                    s"Authentication service credentials are not valid")
            case NonFatal(e) =>
                throw new InternalServerErrorHttpException(e.getMessage)
        }
    }

    private def asResource(tenant: AuthTenant): Tenant = {
        new Tenant(uriInfo.getBaseUri, tenant.getId, tenant.getName,
                   tenant.getDescription, tenant.isEnabled)
    }

}
