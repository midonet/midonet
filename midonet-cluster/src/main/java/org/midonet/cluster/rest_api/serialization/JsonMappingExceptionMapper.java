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
package org.midonet.cluster.rest_api.serialization;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import com.fasterxml.jackson.databind.JsonMappingException;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static org.midonet.cluster.rest_api.ResponseUtils.buildErrorResponse;

/**
 * ExceptionMapper provider class to handle JsonMappingException.
 */
@Provider
public class JsonMappingExceptionMapper
    implements ExceptionMapper<JsonMappingException> {

    @Override
    public Response toResponse(JsonMappingException e) {
        return buildErrorResponse(BAD_REQUEST.getStatusCode(),
            "Invalid fields or values were passed in that could not be" +
            " deserialized into a known object.  Please check" +
            " fields such as 'type'.");
    }
}
