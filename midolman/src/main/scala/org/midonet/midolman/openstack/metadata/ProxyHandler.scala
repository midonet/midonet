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

package org.midonet.midolman.openstack.metadata

import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import com.sun.jersey.api.client.UniformInterfaceException

import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler

import org.midonet.midolman.config.MidolmanConfig

class ProxyHandler(val config: MidolmanConfig) extends AbstractHandler {

    def handle(target: String, baseReq: Request, request: HttpServletRequest,
               response: HttpServletResponse) = {
        baseReq setHandled true
        try {
            val result = NovaMetadataClient.proxyRequest(
                                request.getMethod,
                                request.getPathInfo,
                                request.getInputStream,
                                request.getRemoteAddr,
                                config.openstack.metadata.novaMetadataUrl,
                                config.openstack.metadata.sharedSecret)
            response.getWriter print result
        } catch {
            case e: UniformInterfaceException =>
                response.sendError(e.getResponse.getStatus, e.getMessage)
            case e: NovaMetadataClientException =>
                response.sendError(HttpServletResponse.SC_FORBIDDEN, e.getMessage)
        }
    }
}
