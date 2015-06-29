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
package org.midonet.cluster.rest_api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;
import javax.validation.ConstraintViolation;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.midonet.cluster.rest_api.models.ErrorEntity;
import org.midonet.cluster.rest_api.models.ValidationErrorEntity;
import org.midonet.util.http.HttpSupport;

public class ResponseUtils {

    private static ObjectMapper objectMapper = new ObjectMapper();
    private static JsonFactory jsonFactory = new JsonFactory(objectMapper);

    public static Response buildErrorResponse(int status, String message) {
        ErrorEntity error = new ErrorEntity();
        error.setCode(status);
        error.setMessage(message);
        return Response.status(status).entity(error)
                .type(VendorMediaType.APPLICATION_ERROR_JSON).build();
    }

    public static Response buildErrorResponse(int status, String message,
                                              Map<String, Object> header) {
        ErrorEntity error = new ErrorEntity();
        error.setCode(status);
        error.setMessage(message);
        Response.ResponseBuilder response = Response.status(status).entity(error)
                .type(VendorMediaType.APPLICATION_ERROR_JSON);
        for (Map.Entry<String, Object> entry : header.entrySet()) {
            response.header(entry.getKey(), entry.getValue());
        }
        return response.build();
    }

    public static <T> Response buildValidationErrorResponse(
            Set<ConstraintViolation<T>> violations) {
        ValidationErrorEntity errors = new ValidationErrorEntity();
        errors.setMessage("Validation error(s) found");
        errors.setCode(Response.Status.BAD_REQUEST.getStatusCode());
        List<Map<String, String>> messages = new ArrayList<>(
                violations.size());
        for (ConstraintViolation<T> c : violations) {
            Map<String, String> msg = new HashMap<>(2);
            msg.put("property", c.getPropertyPath().toString());
            msg.put("message", c.getMessage());
            messages.add(msg);
        }
        errors.setViolations(messages);
        return Response.status(Response.Status.BAD_REQUEST).entity(errors)
                .type(VendorMediaType.APPLICATION_ERROR_JSON).build();
    }

    public static String generateJsonError(int code, String msg)
            throws IOException {
        ErrorEntity err = new ErrorEntity();
        err.setCode(code);
        err.setMessage(msg);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        JsonGenerator jsonGenerator =
            jsonFactory.createGenerator(bos, JsonEncoding.UTF8);
        jsonGenerator.writeObject(err);
        bos.close();
        return bos.toString(HttpSupport.UTF8_ENC);
    }

    public static void setErrorResponse(HttpServletResponse resp, int code,
                                        String msg) throws IOException {
        resp.setContentType(VendorMediaType.APPLICATION_ERROR_JSON);
        resp.setCharacterEncoding(HttpSupport.UTF8_ENC);
        resp.setStatus(code);
        resp.getWriter().write(generateJsonError(code, msg));
    }

    public static void setAuthErrorResponse(HttpServletResponse resp,
                                        String msg) throws IOException {
        setErrorResponse(resp, HttpServletResponse.SC_UNAUTHORIZED, msg);
        resp.setHeader(HttpSupport.WWW_AUTHENTICATE,
                HttpSupport.BASIC_AUTH_REALM_FIELD);
    }

    public static void setCookie(HttpServletResponse resp, String key,
                                 String expires) {

        StringBuilder sb = new StringBuilder();
        sb.append(HttpSupport.SET_COOKIE_SESSION_KEY);
        sb.append("=");
        sb.append(key);
        if (expires != null) {
            sb.append("; ");
            sb.append(HttpSupport.SET_COOKIE_EXPIRES);
            sb.append("=");
            sb.append(expires);
        }

        resp.setHeader(HttpSupport.SET_COOKIE, sb.toString());
    }

    /**
     * Sets the serialized form of the object passed to the body
     * of the response.
     *
     * @param resp the Response whose body we will modify.
     * @param entity the object to serialize and put in the response.
     * @throws IOException
     */
    public static void setEntity(HttpServletResponse resp, Object entity)
            throws IOException {
        resp.setContentType(VendorMediaType.APPLICATION_TOKEN_JSON);
        resp.setCharacterEncoding(HttpSupport.UTF8_ENC);
        resp.setStatus(HttpServletResponse.SC_OK);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        JsonGenerator jsonGenerator =
            jsonFactory.createGenerator(bos, JsonEncoding.UTF8);
        jsonGenerator.writeObject(entity);
        bos.close();
        resp.getWriter().write(bos.toString());
    }
}
