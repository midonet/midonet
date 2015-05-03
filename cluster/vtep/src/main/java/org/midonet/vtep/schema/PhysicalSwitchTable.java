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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.cluster.data.vtep.model.VtepEntry;
import org.midonet.packets.IPv4Addr;
import org.midonet.cluster.data.vtep.model.PhysicalSwitch;

import static org.midonet.vtep.OvsdbTranslator.fromOvsdb;
import static org.midonet.vtep.OvsdbTranslator.fromOvsdbIpSet;
import static org.midonet.vtep.OvsdbTranslator.toOvsdb;
import static org.midonet.vtep.OvsdbTranslator.toOvsdbIpSet;

/**
 * Schema for the Ovsdb physical switch table
 */
public final class PhysicalSwitchTable extends Table {
    static public final String TB_NAME = "Physical_Switch";
    static private final String COL_NAME = "name";
    static private final String COL_DESCRIPTION = "description";
    static private final String COL_PORTS = "ports";
    static private final String COL_MANAGEMENT_IPS = "management_ips";
    static private final String COL_TUNNEL_IPS = "tunnel_ips";

    public PhysicalSwitchTable(DatabaseSchema databaseSchema) {
        super(databaseSchema, TB_NAME, PhysicalSwitch.class);
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
        cols.add(getPortsSchema());
        cols.add(getManagementIpsSchema());
        cols.add(getTunnelIpsSchema());
        return cols;
    }

    /** Get the schema for the name of the physical switch (id) */
    private ColumnSchema<GenericTableSchema, String> getNameSchema() {
        return tableSchema.column(COL_NAME, String.class);
    }

    /** Get the schema for the description of the physical switch */
    private ColumnSchema<GenericTableSchema, String> getDescriptionSchema() {
        return tableSchema.column(COL_DESCRIPTION, String.class);
    }

    /** Get the schema for the list of internal port ids */
    private ColumnSchema<GenericTableSchema, Set> getPortsSchema() {
        return tableSchema.column(COL_PORTS, Set.class);
    }

    /** Get the schema for the list of management ips */
    private ColumnSchema<GenericTableSchema, Set> getManagementIpsSchema() {
        return tableSchema.column(COL_MANAGEMENT_IPS, Set.class);
    }

    /** Get the schema for the list of tunnel ips */
    private ColumnSchema<GenericTableSchema, Set> getTunnelIpsSchema() {
        return tableSchema.column(COL_TUNNEL_IPS, Set.class);
    }

    /** Generate a matcher condition for the management ips
     * (for use with select) */
    static public Condition getManagementIpsMatcher(IPv4Addr value) {
        return new Condition(COL_MANAGEMENT_IPS, Function.INCLUDES,
                             value.toString());
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
     * Extract the set of physical port names
     */
    @SuppressWarnings(value = "unckecked")
    private Set<UUID> parsePorts(Row<GenericTableSchema> row) {
        return fromOvsdb(extractSet(row, getPortsSchema()));
    }

    /**
     * Extract the set of management ips
     */
    private Set<IPv4Addr> parseManagementIps(Row<GenericTableSchema> row) {
        return fromOvsdbIpSet(extractSet(row, getManagementIpsSchema()));
    }

    /**
     * Extract the set of tunnel ips (may be empty)
     */
    private Set<IPv4Addr> parseTunnelIps(Row<GenericTableSchema> row) {
        return fromOvsdbIpSet(extractSet(row, getTunnelIpsSchema()));
    }

    /**
     * Extract the physical switch information from the table entry
     */
    @Override
    @SuppressWarnings(value = "unckecked")
    public <E extends VtepEntry>
    E parseEntry(Row<GenericTableSchema> row, Class<E> clazz)
        throws IllegalArgumentException {
        ensureOutputClass(clazz);
        return (row == null)? null:
               (E)PhysicalSwitch.apply(parseUuid(row), parseName(row),
                                      parseDescription(row), parsePorts(row),
                                      parseManagementIps(row),
                                      parseTunnelIps(row));
    }

    /**
     * Insertion of physical port information
     */
    @Override
    public <E extends VtepEntry> Table.OvsdbInsert insert(E row)
        throws IllegalArgumentException {
        Insert<GenericTableSchema> op = newInsert(row);
        PhysicalSwitch ps = (PhysicalSwitch)row;
        op.value(getNameSchema(), ps.name());
        op.value(getDescriptionSchema(), ps.description());
        op.value(getPortsSchema(), toOvsdb(ps.ports()));
        op.value(getManagementIpsSchema(), toOvsdbIpSet(ps.mgmtIps()));
        op.value(getTunnelIpsSchema(), toOvsdbIpSet(ps.tunnelIps()));
        return new OvsdbInsert(op);
    }

    @Override
    public <E extends VtepEntry> Row<GenericTableSchema> generateRow(
        E entry) throws IllegalArgumentException {
        Row<GenericTableSchema> row = super.generateRow(entry);
        PhysicalSwitch data = (PhysicalSwitch)entry;
        addToRow(row, getNameSchema(), data.name());
        addToRow(row, getDescriptionSchema(), data.description());
        addToRow(row, getPortsSchema(), toOvsdb(data.ports()));
        addToRow(row, getManagementIpsSchema(), toOvsdbIpSet(data.mgmtIps()));
        addToRow(row, getTunnelIpsSchema(), toOvsdbIpSet(data.tunnelIps()));
        return row;
    }
}
