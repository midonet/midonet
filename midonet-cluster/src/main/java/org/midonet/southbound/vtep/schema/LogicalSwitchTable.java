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

package org.midonet.southbound.vtep.schema;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.cluster.data.vtep.model.LogicalSwitch;

/**
 * Schema for the Ovsdb logical switch table
 */
public final class LogicalSwitchTable extends Table<LogicalSwitch> {
    static public final String TB_NAME = "Logical_Switch";
    static private final String COL_NAME = "name";
    static private final String COL_DESCRIPTION = "description";
    static private final String COL_TUNNEL_KEY = "tunnel_key";

    public LogicalSwitchTable(DatabaseSchema databaseSchema) {
        super(databaseSchema, TB_NAME, LogicalSwitch.class);
    }

    public String getName() {
        return TB_NAME;
    }

    /** Get the schema of the columns of this table */
    @Override
    public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols =
            super.partialColumnSchemas();
        cols.add(getNameSchema());
        cols.add(getDescriptionSchema());
        // This column is optional in the OVSDB schema, but we must have it.
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
    @Override
    @SuppressWarnings(value = "unchecked")
    public LogicalSwitch parseEntry(Row<GenericTableSchema> row)
        throws IllegalArgumentException {
        ensureOutputClass(LogicalSwitch.class);
        return (row == null)? null:
               LogicalSwitch.apply(parseUuid(row), parseName(row),
                                   parseTunnelKey(row),
                                   parseDescription(row));
    }

    /**
     * Generate an insertion operation for a logical switch
     */
    @Override
    public Table.OvsdbInsert insert(LogicalSwitch entry, String id)
        throws IllegalArgumentException {
        Insert<GenericTableSchema> op = newInsert(entry, id);
        Set<Long> vni = new HashSet<>();
        if (entry.tunnelKey() != null) {
            vni.add((long) entry.tunnelKey());
        }
        op.value(getNameSchema(), entry.name());
        op.value(getDescriptionSchema(), entry.description());
        op.value(getTunnelKeySchema(), vni);
        return new OvsdbInsert(op);
    }

    @Override
    public Table.OvsdbDelete delete(LogicalSwitch entry) {
        return deleteById(entry.uuid());
    }

    @Override
    public Row<GenericTableSchema> generateRow(LogicalSwitch entry)
        throws IllegalArgumentException {
        Row<GenericTableSchema> row = super.generateRow(entry);
        Set<Long> vni = new HashSet<>();
        if (entry.tunnelKey() != null) {
            vni.add((long) entry.tunnelKey());
        }
        addToRow(row, getNameSchema(), entry.name());
        addToRow(row, getDescriptionSchema(), entry.description());
        addToRow(row, getTunnelKeySchema(), vni);
        return row;
    }
}
