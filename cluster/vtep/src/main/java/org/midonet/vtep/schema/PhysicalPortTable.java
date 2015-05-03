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
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.operations.Update;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.cluster.data.vtep.model.PhysicalPort;
import org.midonet.cluster.data.vtep.model.VtepEntry;

import static scala.collection.JavaConversions.setAsJavaSet;
import static org.midonet.vtep.OvsdbTranslator.fromOvsdb;
import static org.midonet.vtep.OvsdbTranslator.toOvsdb;

/**
 * Schema for the Ovsdb physical port table
 */
public final class PhysicalPortTable extends Table {
    static public final String TB_NAME = "Physical_Port";
    static private final String COL_NAME = "name";
    static private final String COL_DESCRIPTION = "description";
    static private final String COL_VLAN_BINDINGS = "vlan_bindings";
    static private final String COL_VLAN_STATS = "vlan_stats";
    static private final String COL_PORT_FAULT_STATUS = "port_fault_status";

    public PhysicalPortTable(DatabaseSchema databaseSchema) {
        super(databaseSchema, TB_NAME, PhysicalPort.class);
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
        cols.add(getVlanBindingsSchema());
        cols.add(getVlanStatsSchema());
        cols.add(getPortFaultStatusSchema());
        return cols;
    }

    /** Get the schema for the name of the physical port (id) */
    private ColumnSchema<GenericTableSchema, String> getNameSchema() {
        return tableSchema.column(COL_NAME, String.class);
    }

    /** Get the schema for the description of the physical port */
    private ColumnSchema<GenericTableSchema, String> getDescriptionSchema() {
        return tableSchema.column(COL_DESCRIPTION, String.class);
    }

    /** Get the schema for the map of bindings */
    private ColumnSchema<GenericTableSchema, Map> getVlanBindingsSchema() {
        return tableSchema.column(COL_VLAN_BINDINGS, Map.class);
    }

    /** Get the schema for the map of vlan stats */
    private ColumnSchema<GenericTableSchema, Map> getVlanStatsSchema() {
        return tableSchema.column(COL_VLAN_STATS, Map.class);
    }

    /** Get the schema for the port fault status */
    private ColumnSchema<GenericTableSchema, Set> getPortFaultStatusSchema() {
        return tableSchema.column(COL_PORT_FAULT_STATUS, Set.class);
    }

    /** Generate a matcher condition for the port name */
    static public Condition getNameMatcher(String value) {
        return new Condition(COL_NAME, Function.EQUALS, value);
    }

    /**
     * Extract the name
     */
    private String parseName(Row<GenericTableSchema> row) {
        return extractString(row, getNameSchema());
    }

    /**
     * Extract the description
     */
    private String parseDescription(Row<GenericTableSchema> row) {
        return extractString(row, getDescriptionSchema());
    }

    /** Extract vlan - logical switch id mappings */
    private Map<Integer, java.util.UUID> parseVlanBindings(
        Row<GenericTableSchema> row) {
        return fromOvsdb(extractMap(row, getVlanBindingsSchema()));
    }

    /** Extract vlan - stats id mappings */
    private Map<Integer, java.util.UUID> parseVlanStats(
        Row<GenericTableSchema> row) {
        return fromOvsdb(extractMap(row, getVlanStatsSchema()));
    }

    /**
     * Extract the port fault status
     */
    @SuppressWarnings(value = "unckecked")
    private Set<String> parsePortFaultStatus(Row<GenericTableSchema> row) {
        return (Set<String>)extractSet(row, getPortFaultStatusSchema());
    }

    /**
     * Extract the physical port information from the table entry
     */
    @Override
    @SuppressWarnings(value = "unckecked")
    public <E extends VtepEntry> E parseEntry(Row<GenericTableSchema> row,
                                              Class<E> clazz)
        throws IllegalArgumentException {
        ensureOutputClass(clazz);
        return (row == null)? null:
               (E)PhysicalPort.apply(parseUuid(row), parseName(row),
                                     parseDescription(row),
                                     parseVlanBindings(row),
                                     parseVlanStats(row),
                                     parsePortFaultStatus(row));
    }

    /**
     * Insertion of physical port information
     */
    @Override
    public <E extends VtepEntry> Table.OvsdbInsert insert(E row)
        throws IllegalArgumentException {
        Insert<GenericTableSchema> op = newInsert(row);
        PhysicalPort port = (PhysicalPort)row;
        op.value(getNameSchema(), port.name());
        op.value(getDescriptionSchema(), port.description());
        op.value(getVlanBindingsSchema(), toOvsdb(port.vlanBindings()));
        op.value(getVlanStatsSchema(), toOvsdb(port.vlanStats()));
        op.value(getPortFaultStatusSchema(),
                 setAsJavaSet(port.portFaultStatus()));
        return new OvsdbInsert(op);
    }

    /**
     * Modification of binding information
     */
    public Table.OvsdbUpdate updateBindings(PhysicalPort port) {
        Update<GenericTableSchema> op = new Update<>(tableSchema);
        op.set(getVlanBindingsSchema(), toOvsdb(port.vlanBindings()));
        op.set(getVlanStatsSchema(), toOvsdb(port.vlanStats()));
        op.where(getUuidMatcher(port.uuid()));
        op.where(getNameMatcher(port.name()));
        return new OvsdbUpdate(op);
    }

    @Override
    public <E extends VtepEntry> Row<GenericTableSchema> generateRow(
        E entry) throws IllegalArgumentException {
        Row<GenericTableSchema> row = super.generateRow(entry);
        PhysicalPort data = (PhysicalPort)entry;
        addToRow(row, getNameSchema(), data.name());
        addToRow(row, getDescriptionSchema(), data.description());
        addToRow(row, getVlanBindingsSchema(), toOvsdb(data.vlanBindings()));
        addToRow(row, getVlanStatsSchema(), toOvsdb(data.vlanStats()));
        addToRow(row, getPortFaultStatusSchema(),
                 setAsJavaSet(data.portFaultStatus()));
        return row;
    }
}
