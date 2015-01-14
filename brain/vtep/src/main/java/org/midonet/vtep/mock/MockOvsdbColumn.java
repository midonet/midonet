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

package org.midonet.vtep.mock;

import java.util.Set;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.ColumnType;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

/**
 * Tools to generate ovsdb column schemas
 */
public class MockOvsdbColumn {
    static private JsonNode stringToJson(String str)
        throws IllegalArgumentException {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readTree(str);
        } catch (Throwable t) {
            // Catch parsing errors
            throw new IllegalArgumentException(t);
        }
    }

    static public <D> ColumnSchema<GenericTableSchema, D> mkColumnSchema(
        String name, Class<D> clazz) throws IllegalArgumentException {
        JsonNode jsonType;
        if (clazz.equals(UUID.class)) {
            jsonType = stringToJson(
                "{ \"key\": \"uuid\", \"min\": 1, \"max\": 1}");
        } else if (clazz.equals(Long.class)) {
            jsonType = stringToJson(
                "{ \"key\": \"integer\", \"min\": 1, \"max\": 1}");
        } else if (clazz.equals(String.class)) {
            jsonType = stringToJson(
                "{ \"key\": \"string\", \"min\": 1, \"max\": 1}");
        } else if (clazz.equals(Boolean.class)) {
            jsonType = stringToJson(
                "{ \"key\": \"boolean\", \"min\": 1, \"max\": 1}");
        } else {
            throw new IllegalArgumentException(
                "cannot generate schema for type " + clazz);
        }
        return new ColumnSchema<>(
            name, ColumnType.AtomicColumnType.fromJson(jsonType));
    }

    static public <D> ColumnSchema<GenericTableSchema, Set> mkSetColumnSchema(
        String name, Class<?> clazz) throws IllegalArgumentException {
        JsonNode jsonType;
        if (clazz.equals(UUID.class)) {
            jsonType = stringToJson(
                "{ \"key\": \"uuid\", \"min\": 0, \"max\": \"unlimited\"}");
        } else if (clazz.equals(Long.class)) {
            jsonType = stringToJson(
                "{ \"key\": \"integer\", \"min\": 0, \"max\": \"unlimited\"}");
        } else if (clazz.equals(String.class)) {
            jsonType = stringToJson(
                "{ \"key\": \"string\", \"min\": 0, \"max\": \"unlimited\"}");
        } else if (clazz.equals(Boolean.class)) {
            jsonType = stringToJson(
                "{ \"key\": \"boolean\", \"min\": 0, \"max\": \"unlimited\"}");
        } else {
            throw new IllegalArgumentException(
                "cannot generate schema for type " + clazz);
        }
        return new ColumnSchema<>(
            name, ColumnType.AtomicColumnType.fromJson(jsonType));
    }


}
