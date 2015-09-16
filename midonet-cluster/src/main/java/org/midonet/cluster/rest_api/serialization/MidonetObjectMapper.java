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

package org.midonet.cluster.rest_api.serialization;

import java.io.IOException;

import javax.ws.rs.WebApplicationException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.introspect.BasicBeanDescription;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

import org.apache.commons.lang.StringUtils;

import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.rest_api.ResponseUtils;
import org.midonet.cluster.rest_api.annotation.JsonError;
import org.midonet.cluster.rest_api.validation.MessageProperty;

/**
 * A JSON {@link ObjectMapper} for MidoNet API resources. This implementation
 * provides a custom handling of serialization exceptions, such that it returns
 * error responses and messages equivalent to legacy validators.
 */
public class MidonetObjectMapper extends ObjectMapper {

    @Override
    protected ObjectReader _newReader(DeserializationConfig config) {
        return new MidonetObjectReader(this, config);
    }

    @Override
    protected Object _readValue(DeserializationConfig config, JsonParser jp,
                                JavaType valueType)
        throws IOException {
        try {
            return super._readValue(config, jp, valueType);
        } catch(JsonMappingException e) {
            throw wrapException(e, config, valueType);
        }
    }

    static WebApplicationException wrapException(JsonMappingException e,
                                                 DeserializationConfig config,
                                                 JavaType valueType) {
        if (e.getPath().size() == 0)
            return new BadRequestHttpException(e.getMessage());
        JsonMappingException.Reference ref = e.getPath().get(0);
        if (null == ref || null == ref.getFrom())
            return new BadRequestHttpException(e.getMessage());

        JsonError error = getError(config, valueType, ref.getFieldName());
        if (null == error)
            return new BadRequestHttpException(e.getMessage());

        String message;
        if (StringUtils.isNotEmpty(error.message())) {
            message = MessageProperty.getMessage(error.message());
        } else {
            message = error.value();
        }
        return new WebApplicationException(
            ResponseUtils.buildErrorResponse(error.status(), message));
    }

    static JsonError getError(DeserializationConfig config,
                              JavaType valueType, String fieldName) {
        try {
            BasicBeanDescription beanDesc = config.introspect(valueType);
            for (BeanPropertyDefinition property : beanDesc.findProperties()) {
                if (property.getName().equals(fieldName) &&
                    null != property.getField() &&
                    null != property.getField().getAnnotated()) {
                    return property.getField().getAnnotated()
                                   .getAnnotation(JsonError.class);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

}