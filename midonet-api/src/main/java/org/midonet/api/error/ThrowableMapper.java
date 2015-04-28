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
package org.midonet.api.error;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import org.slf4j.Logger;

import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static org.midonet.cluster.rest_api.ResponseUtils.buildErrorResponse;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * ExceptionMapper provider class to handle any Throwables.
 */
@Provider
public class ThrowableMapper implements ExceptionMapper<Throwable> {

    private final static Logger log = getLogger(ThrowableMapper.class);


    @Override
    public Response toResponse(Throwable e) {
        log.error("Encountered uncaught exception: ", e);
        return buildErrorResponse(INTERNAL_SERVER_ERROR.getStatusCode(),
            "An internal server error has occurred, please try again.");
    }
}
