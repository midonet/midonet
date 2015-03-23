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

package org.midonet.vtep.schema;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.cluster.data.vtep.model.LogicalSwitch;

/**
 * Schema for the Ovsdb logical switch table
 */
public final class LogicalSwitchTable extends Table {
    static private final String TB_NAME = "Logical_Switch";
    static private final String COL_NAME = "name";
    static private final String COL_DESCRIPTION = "description";
    static private final String COL_TUNNEL_KEY = "tunnel_key";

    public LogicalSwitchTable(DatabaseSchema databaseSchema) {
        super(databaseSchema, TB_NAME);
    }

    /** Get the schema of the columns of this table */
    public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols = super.getColumnSchemas();
        cols.add(getNameSchema());
        cols.add(getDescriptionSchema());
        cols.add(getTunnelKeySchema());
        return cols;
    }

    /** Get the schema for the name of the logical switch (id) */
    private ColumnSchema<GenericTableSchema, String> getNameSchema() {
        return tableSchema.column(COL_NAME, String.class);
    }

    /** Get the schema for the description of the logical switch */
    private ColumnSchema<GenericTableSchema, String> getDescriptionSchema() {
        return tableSchema.column(COL_DESCRIPTION, String.class);
    }

    /** Get the schema for the optional tunnel key (vxlan vni) */
    private ColumnSchema<GenericTableSchema, Set> getTunnelKeySchema() {

        return tableSchema.column(COL_TUNNEL_KEY, Set.class);
    }

    /** Generate a matcher condition for the key uuid (for use with select) */
    public Condition getNameMatcher(String value) {
        return new Condition(COL_NAME, Function.EQUALS, value);
    }

    /**
     * Extract the physical switch name, returning null if not set or empty
     */
    private String parseName(Row<GenericTableSchema> row) {
        return extractString(row, getNameSchema());
    }

    /**
     * Extract the physical switch description
     */
    private String parseDescription(Row<GenericTableSchema> row) {
        return extractString(row, getDescriptionSchema());
    }

    /**
     * Extract the tunnel key, returning null if not set
     */
    private Integer parseTunnelKey(Row<GenericTableSchema> row) {
        Set tunnelKey = (row == null)? null:
            row.getColumn(getTunnelKeySchema()).getData();
        return (tunnelKey == null || tunnelKey.isEmpty())? null:
               ((Long)tunnelKey.iterator().next()).intValue();
    }

    /**
     * Extract a complete logical switch from a table row
     */
    public LogicalSwitch parseLogicalSwitch(Row<GenericTableSchema> row) {
        return new LogicalSwitch(parseUuid(row), parseName(row),
                                 parseTunnelKey(row), parseDescription(row));
    }

    /**
     * Generate an insertion operation for a logical switch
     */
    public Insert<GenericTableSchema> insert(LogicalSwitch ls) {
        Insert<GenericTableSchema> op = super.insert(ls.uuid());
        Set<Long> vni = new HashSet<>();
        if (ls.tunnelKey() != null)
            vni.add((long)ls.tunnelKey());
        op.value(getNameSchema(), ls.name());
        op.value(getDescriptionSchema(), ls.description());
        op.value(getTunnelKeySchema(), vni);
        return op;
    }

}
