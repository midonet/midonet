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

package org.midonet.southbound.vtep.mock;

import java.util.Map;
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

    static private String baseTypeString(Class<?> clazz)
        throws IllegalArgumentException {
        if (clazz.equals(UUID.class)) {
            return "uuid";
        } else if (clazz.equals(Long.class)) {
            return "integer";
        } else if (clazz.equals(String.class)) {
            return "string";
        } else if (clazz.equals(Boolean.class)) {
            return "boolean";
        } else {
            throw new IllegalArgumentException(
                "cannot generate schema for type " + clazz);
        }
    }

    /** Generate a json spec for an atomic value column (single type value)
      * If multivalued is true, it generates a set type. */
    static private JsonNode mkTypeSpec(Class<?> clazz, Boolean multivalued)
        throws IllegalArgumentException {
        String format = multivalued?
                        "{\"key\": \"%s\", \"min\": 0, \"max\": \"unlimited\"}":
                        "{\"key\": \"%s\", \"min\": 1, \"max\": 1}";
        String typeSpec = String.format(format, baseTypeString(clazz));
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readTree(typeSpec);
        } catch (Throwable t) {
            // Catch parsing errors
            throw new IllegalArgumentException(t);
        }
    }

    /** Generate a json spec for a key-val column (map values)
     * If multivalued is true, it generates a map type. */
    static private JsonNode mkTypeSpec(Class<?> kClazz, Class<?> vClazz,
                                       Boolean multivalued)
        throws IllegalArgumentException {
        String format = multivalued?
            "{\"key\": \"%s\", \"value\": \"%s\", \"min\": 0, \"max\": \"unlimited\"}":
            "{\"key\": \"%s\", \"value\": \"%s\", \"min\": 1, \"max\": 1}";
        String typeSpec = String.format(format, baseTypeString(kClazz),
                                        baseTypeString(vClazz));
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readTree(typeSpec);
        } catch (Throwable t) {
            // Catch parsing errors
            throw new IllegalArgumentException(t);
        }
    }


    /** Generate a column schema for a single value column */
    static public <D> ColumnSchema<GenericTableSchema, D> mkColumnSchema(
        String name, Class<D> clazz) throws IllegalArgumentException {
        JsonNode jsonType = mkTypeSpec(clazz, false);
        return new ColumnSchema<>(
            name, ColumnType.AtomicColumnType.fromJson(jsonType));
    }

    /** Generate a column schema for a set column */
    static public ColumnSchema<GenericTableSchema, Set> mkSetColumnSchema(
        String name, Class<?> clazz) throws IllegalArgumentException {
        JsonNode jsonType = mkTypeSpec(clazz, true);
        return new ColumnSchema<>(
            name, ColumnType.AtomicColumnType.fromJson(jsonType));
    }

    /** Generate a column schema for a map column */
    static public ColumnSchema<GenericTableSchema, Map> mkMapColumnSchema(
        String name, Class<?> kClazz, Class<?> vClazz)
        throws IllegalArgumentException {
        JsonNode jsonType = mkTypeSpec(kClazz, vClazz, true);
        return new ColumnSchema<>(
            name, ColumnType.KeyValuedColumnType.fromJson(jsonType));
    }
}
