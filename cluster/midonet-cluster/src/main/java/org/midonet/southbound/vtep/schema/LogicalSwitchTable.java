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

import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.operations.Delete;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.cluster.data.vtep.model.LogicalSwitch;
import org.midonet.cluster.data.vtep.model.VtepEntry;

/**
 * Schema for the Ovsdb logical switch table
 */
public final class LogicalSwitchTable extends Table {
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
    static public Condition getNameMatcher(String value) {
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
    @Override
    @SuppressWarnings(value = "unchecked")
    public <E extends VtepEntry>
    E parseEntry(Row<GenericTableSchema> row, Class<E> clazz)
        throws IllegalArgumentException {
        ensureOutputClass(clazz);
        return (row == null)? null:
               (E)LogicalSwitch.apply(parseUuid(row), parseName(row),
                                      parseTunnelKey(row),
                                      parseDescription(row));
    }

    /**
     * Generate an insertion operation for a logical switch
     */
    @Override
    public <E extends VtepEntry> Table.OvsdbInsert insert(E entry)
        throws IllegalArgumentException {
        Insert<GenericTableSchema> op = newInsert(entry);
        LogicalSwitch ls = (LogicalSwitch)entry;
        Set<Long> vni = new HashSet<>();
        if (ls.tunnelKey() != null)
            vni.add((long)ls.tunnelKey());
        op.value(getNameSchema(), ls.name());
        op.value(getDescriptionSchema(), ls.description());
        op.value(getTunnelKeySchema(), vni);
        return new OvsdbInsert(op);
    }

    /**
     * Generate a delete operation based on logical switch name
     */
    public Table.OvsdbDelete deleteByName(String name) {
        Delete<GenericTableSchema> op = new Delete<>(tableSchema);
        op.where(getNameMatcher(name));
        return new OvsdbDelete(op);
    }

    @Override
    public <E extends VtepEntry> Row<GenericTableSchema> generateRow(
        E entry) throws IllegalArgumentException {
        Row<GenericTableSchema> row = super.generateRow(entry);
        LogicalSwitch ls = (LogicalSwitch)entry;
        Set<Long> vni = new HashSet<>();
        if (ls.tunnelKey() != null)
            vni.add((long)ls.tunnelKey());
        addToRow(row, getNameSchema(), ls.name());
        addToRow(row, getDescriptionSchema(), ls.description());
        addToRow(row, getTunnelKeySchema(), vni);
        return row;
    }
}
