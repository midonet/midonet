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
import java.util.Map;
import java.util.Set;


import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.cluster.data.vtep.model.PhysicalPort;

import static org.midonet.vtep.OvsdbTranslator.fromOvsdb;

/**
 * Schema for the Ovsdb physical port table
 */
public final class PhysicalPortTable extends Table {
    static private final String TB_NAME = "Physical_Port";
    static private final String COL_NAME = "name";
    static private final String COL_DESCRIPTION = "description";
    static private final String COL_VLAN_BINDINGS = "vlan_bindings";
    static private final String COL_VLAN_STATS = "vlan_stats";
    static private final String COL_PORT_FAULT_STATUS = "port_fault_status";

    public PhysicalPortTable(DatabaseSchema databaseSchema) {
        super(databaseSchema, TB_NAME);
    }

    /** Get the schema of the columns of this table */
    public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols = super.getColumnSchemas();
        cols.add(getNameSchema());
        cols.add(getDescriptionSchema());
        cols.add(getVlanBindingsSchema());
        cols.add(getVlanStatsSchema());
        cols.add(getPortFaultStatusSchema());
        return cols;
    }

    /** Get the schema for the name of the physical port (id) */
    public ColumnSchema<GenericTableSchema, String> getNameSchema() {
        return tableSchema.column(COL_NAME, String.class);
    }

    /** Get the schema for the description of the physical port */
    public ColumnSchema<GenericTableSchema, String> getDescriptionSchema() {
        return tableSchema.column(COL_DESCRIPTION, String.class);
    }

    /** Get the schema for the map of bindings */
    public ColumnSchema<GenericTableSchema, Map> getVlanBindingsSchema() {
        return tableSchema.column(COL_VLAN_BINDINGS, Map.class);
    }

    /** Get the schema for the map of vlan stats */
    public ColumnSchema<GenericTableSchema, Map> getVlanStatsSchema() {
        return tableSchema.column(COL_VLAN_STATS, Map.class);
    }

    /** Get the schema for the port fault status */
    public ColumnSchema<GenericTableSchema, Set> getPortFaultStatusSchema() {
        return tableSchema.column(COL_PORT_FAULT_STATUS, Set.class);
    }

    /** Generate a matcher condition for the port name */
    public Condition getNameMatcher(String value) {
        return new Condition(COL_NAME, Function.EQUALS, value);
    }

    /**
     * Extract the name
     */
    public String parseName(Row<GenericTableSchema> row) {
        return extractString(row, getNameSchema());
    }

    /**
     * Extract the description
     */
    public String parseDescription(Row<GenericTableSchema> row) {
        return extractString(row, getDescriptionSchema());
    }

    /** Extract vlan - logical switch id mappings */
    public Map<Integer, java.util.UUID> parseVlanBindings(
        Row<GenericTableSchema> row) {
        return fromOvsdb(extractMap(row, getVlanBindingsSchema()));
    }

    /** Extract vlan - stats id mappings */
    public Map<Integer, java.util.UUID> parseVlanStats(
        Row<GenericTableSchema> row) {
        return fromOvsdb(extractMap(row, getVlanStatsSchema()));
    }

    /**
     * Extract the port fault status
     */
    @SuppressWarnings(value = "unckecked")
    public Set<String> parsePortFaultStatus(Row<GenericTableSchema> row) {
        return (Set<String>)extractSet(row, getPortFaultStatusSchema());
    }

    /**
     * Extract the physical port information from the table entry
     */
    public PhysicalPort parsePhysicalPort(Row<GenericTableSchema> row) {
        return new PhysicalPort(parseUuid(row), parseName(row),
                                parseDescription(row),
                                parseVlanBindings(row),
                                parseVlanStats(row),
                                parsePortFaultStatus(row));
    }
}
