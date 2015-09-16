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

import java.util.HashMap;
import java.util.Map;


import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;
import org.opendaylight.ovsdb.lib.schema.TableSchema;

/**
 * A class to generate mock ovsdb database schemas
 */
public class MockOvsdbDatabase {
    static public DatabaseSchema get(String name,
                                     Map<String, GenericTableSchema> tables) {
        // The following is necessary to allow use from scala
        Map<String, TableSchema> map = new HashMap<>();
        for (Map.Entry<String, GenericTableSchema> e: tables.entrySet()) {
            map.put(e.getKey(), e.getValue());
        }
        return new DatabaseSchema(name, null, map);
    }
}
