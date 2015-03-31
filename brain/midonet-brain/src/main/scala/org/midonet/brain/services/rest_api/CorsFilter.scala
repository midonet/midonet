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

package org.midonet.brain.services.rest_api

import com.sun.jersey.spi.container.{ContainerRequest, ContainerResponse}
import org.slf4j.LoggerFactory

class CorsFilter {

    private val log = LoggerFactory.getLogger(classOf[CorsFilter])

    @Override
    def filter(request: ContainerRequest, response: ContainerResponse)
    : ContainerResponse = {
        log.info("Filter entered")
        response.getHttpHeaders.add("Access-Control-Allow-Origin", "*")
        response.getHttpHeaders.add("Access-Control-Allow-Headers",
                                    "Origin, X-Auth-Token, Content-Type, Accept, Authorization")
        response.getHttpHeaders.add("Access-Control-Expose-Headers", "Location")
        response.getHttpHeaders.add("Access-Control-Allow-Methods",
                                    "GET, POST, PUT, DELETE, OPTIONS")
        response
    }
}
