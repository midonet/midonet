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


import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

/**
 * A class to generate mock ovsdb table schemas
 */
public class MockOvsdbTable extends GenericTableSchema {
    public MockOvsdbTable(
        String name, Map<String, ColumnSchema<GenericTableSchema, ?>> columns) {
        super(name);
        // The following is necessary to make ovsdb types usable from scala
        Map<String, ColumnSchema> map = new HashMap<>();
        for (Map.Entry<String, ColumnSchema<GenericTableSchema, ?>> e:
            columns.entrySet()) {
            map.put(e.getKey(), (ColumnSchema)e.getValue());
        }
        super.setColumns(map);
    }
}
