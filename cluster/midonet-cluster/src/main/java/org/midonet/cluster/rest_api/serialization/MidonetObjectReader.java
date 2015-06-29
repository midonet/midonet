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

import com.fasterxml.jackson.core.FormatSchema;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.InjectableValues;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.deser.DataFormatReaders;

/**
 * A JSON {@link ObjectReader} for MidoNet resources. This implementation
 * provides a custom handling of serialization exceptions, such that it returns
 * error responses and messages equivalent to legacy validators.
 */
public class MidonetObjectReader extends ObjectReader {

    public MidonetObjectReader(ObjectMapper mapper, DeserializationConfig config) {
        super(mapper, config);
    }

    protected MidonetObjectReader(ObjectReader base, JsonFactory f) {
        super(base, f);
    }

    protected MidonetObjectReader(ObjectReader base,
                                  DeserializationConfig config) {
        super(base, config);
    }

    protected MidonetObjectReader(ObjectReader base,
                                  DeserializationConfig config,
                                  JavaType valueType,
                                  JsonDeserializer<Object> rootDeser,
                                  Object valueToUpdate, FormatSchema schema,
                                  InjectableValues injectableValues,
                                  DataFormatReaders dataFormatReaders) {
        super(base, config, valueType, rootDeser, valueToUpdate, schema,
              injectableValues, dataFormatReaders);
    }

    @Override
    protected Object _bind(JsonParser jp, Object valueToUpdate)
        throws IOException {
        try {
            return super._bind(jp, valueToUpdate);
        } catch(JsonMappingException e) {
            throw MidonetObjectMapper.wrapException(e, _config, _valueType);
        }
    }

    @Override
    protected ObjectReader _new (ObjectReader base, JsonFactory f) {
        return new MidonetObjectReader(base, f);
    }

    @Override
    protected ObjectReader _new(ObjectReader base, DeserializationConfig config) {
        return new MidonetObjectReader(base, config);
    }

    @Override
    protected ObjectReader _new(ObjectReader base, DeserializationConfig config,
                                JavaType valueType,
                                JsonDeserializer<Object> rootDeser,
                                Object valueToUpdate, FormatSchema schema,
                                InjectableValues injectableValues,
                                DataFormatReaders dataFormatReaders) {
        return new MidonetObjectReader(base, config, valueType, rootDeser,
                                       valueToUpdate, schema, injectableValues,
                                       dataFormatReaders);
    }
}
