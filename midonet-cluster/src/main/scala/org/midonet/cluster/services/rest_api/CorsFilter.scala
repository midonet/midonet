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

package org.midonet.cluster.services.rest_api

import javax.servlet._
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import com.google.inject.Singleton
import com.sun.jersey.spi.container.{ContainerRequest, ContainerResponse, ContainerResponseFilter}
import org.slf4j.LoggerFactory

import org.midonet.util.http.HttpSupport._


object CorsFilter {
    val ALLOWED_ORIGINS = "*"
    val ALLOWED_HEADERS = "Origin, X-Auth-Token, Content-Type, Accept, Authorization"
    val EXPOSED_HEADERS = "Location"
    val ALLOWED_METHODS = "GET, POST, PUT, DELETE, OPTIONS"
}

/** Called by the container each time a request/response pair is passed
  * through the chain due to a client request for a resource at the end
  * of the chain. This adds "Access-Control-Allow-Origin" to the headers
  * to allow clients CORS. Refer the following pages to get more details.
  *
  * @see <a href="http://en.wikipedia.org/wiki/Same_origin_policy">
  *     Same origin policy - Wikipedia, the free encyclopedia
  *     </a>
  * @see <a href="http://www.w3.org/TR/cors/#cross-origin-request-with-
  *               preflight0">
  *     Cross-Origin Resource Sharing
  *    </a>
  * @see <a href="http://stackoverflow.com/questions/5406350/access-control-
  *               allow-origin-has-no-influence-in-rest-web-service">
  *     Access-Control-Allow-Origin:* has no influence in REST Web Service
  *     </a>
  */
@Singleton
class CorsFilter extends ContainerResponseFilter with Filter {

    import CorsFilter._

    private val log = LoggerFactory.getLogger("org.midonet.rest_api")

    override def filter(request: ContainerRequest,
                        response: ContainerResponse): ContainerResponse = {
        response.getHttpHeaders.add(ACCESS_CONTROL_ALLOW_ORIGIN_KEY,
                                    ALLOWED_ORIGINS)
        response.getHttpHeaders.add(ACCESS_CONTROL_ALLOW_HEADERS_KEY,
                                    ALLOWED_HEADERS)
        response.getHttpHeaders.add(ACCESS_CONTROL_EXPOSE_HEADERS_KEY,
                                    EXPOSED_HEADERS)
        response.getHttpHeaders.add(ACCESS_CONTROL_ALLOW_METHODS_KEY,
                                    ALLOWED_METHODS)
        response
    }

    override def init(filterConfig: FilterConfig): Unit = {}

    override def destroy(): Unit = {}

    override def doFilter(servletRequest: ServletRequest,
                          servletResponse: ServletResponse,
                          filterChain: FilterChain) = {
        val req = servletRequest.asInstanceOf[HttpServletRequest]
        val res = servletResponse.asInstanceOf[HttpServletResponse]

        // This should be added in response to both the preflight and the
        // actual request
        res.addHeader(ACCESS_CONTROL_ALLOW_ORIGIN_KEY, ALLOWED_ORIGINS)
        res.addHeader(ACCESS_CONTROL_ALLOW_METHODS_KEY, ALLOWED_METHODS)
        res.addHeader(ACCESS_CONTROL_EXPOSE_HEADERS_KEY, EXPOSED_HEADERS)

        if (OPTIONS_METHOD.equalsIgnoreCase(req.getMethod)) {
            // TODO: Credentials are ignored.  Handle credentials when the
            // allow origin header is not wild-carded.
            res.addHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS_KEY, null)
            res.addHeader(ACCESS_CONTROL_ALLOW_HEADERS_KEY, ALLOWED_HEADERS)
            log.debug("CorsFilter handled OPTION request")
        } else {
            filterChain.doFilter(req, res)
        }
    }

}
