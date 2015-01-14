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

import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.operations.Delete;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.cluster.data.vtep.model.VtepEntry;
import org.midonet.packets.IPv4Addr;
import org.midonet.cluster.data.vtep.model.MacEntry;
import org.midonet.cluster.data.vtep.model.VtepMAC;

import static org.midonet.vtep.OvsdbTranslator.toOvsdb;

/**
 * Common schema sections for the {Ucast|Mcast}Mac{Local|Remote} tables
 */
public abstract class MacsTable extends Table {
    static private final String COL_MAC = "MAC";
    static private final String COL_LOGICAL_SWITCH = "logical_switch";
    static private final String COL_IPADDR = "ipaddr";

    protected MacsTable(DatabaseSchema databaseSchema, String tableName,
                        Class<? extends VtepEntry> entryClass) {
        super(databaseSchema, tableName, entryClass);
    }

    /** Get the schema of the columns of this table */
    @Override
    protected List<ColumnSchema<GenericTableSchema, ?>> partialColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols =
            super.partialColumnSchemas();
        cols.add(getMacSchema());
        cols.add(getLogicalSwitchSchema());
        cols.add(getIpaddrSchema());
        return cols;
    }

    /** Get the schema for the MAC column */
    protected ColumnSchema<GenericTableSchema, String> getMacSchema() {
        return tableSchema.column(COL_MAC, String.class);
    }

    /** Get the schema for the logical switch column */
    protected ColumnSchema<GenericTableSchema, UUID> getLogicalSwitchSchema() {
        return tableSchema.column(COL_LOGICAL_SWITCH, UUID.class);
    }

    /** Get the schema for the ipaddr column */
    protected ColumnSchema<GenericTableSchema, String> getIpaddrSchema() {
        return tableSchema.column(COL_IPADDR, String.class);
    }

    /** Get the schema for the location id column
     *  varies for ucast/mcast */
    abstract protected ColumnSchema<GenericTableSchema, UUID> getLocationIdSchema();

    /** Generate a condition to match Mac address */
    static public Condition getMacMatcher(VtepMAC mac) {
        return new Condition(COL_MAC, Function.EQUALS, mac.toString());
    }

    /** Generate a condition to match logical switch */
    static public Condition getLogicalSwitchMatcher(java.util.UUID lsId) {
        return new Condition(COL_LOGICAL_SWITCH, Function.EQUALS, toOvsdb(lsId));
    }

    /** Generate a condition to match ip address */
    static public Condition getIpaddrMatcher(IPv4Addr ip) {
        return new Condition(COL_IPADDR, Function.EQUALS,
                             (ip == null)? null: ip.toString());
    }

    /**
     * Extract the MAC from a table row, returning null if not set or empty
     */
    protected VtepMAC parseMac(Row<GenericTableSchema> row) {
        String value = extractString(row, getMacSchema());
        return (value == null)? VtepMAC.UNKNOWN_DST(): VtepMAC.fromString(value);
    }

    /**
     * Extract the logical switch internal id, to be used as a key for the
     * Logical_Switch table.
     */
    protected java.util.UUID parseLogicalSwitch(Row<GenericTableSchema> row) {
        return extractUuid(row, getLogicalSwitchSchema());
    }

    /**
     * Extract the IpAddr associated to the MAC, returning null if not set or
     * empty
     */
    protected IPv4Addr parseIpaddr(Row<GenericTableSchema> row) {
        String value = extractString(row, getIpaddrSchema());
        return (value == null)? null: IPv4Addr.fromString(value);
    }

    /**
     * Generate an insert operation
     */
    @Override
    public <E extends VtepEntry> Table.OvsdbInsert insert(E row)
        throws IllegalArgumentException {
        Insert<GenericTableSchema> op = newInsert(row);
        MacEntry entry = (MacEntry)row;
        op.value(getMacSchema(), entry.macString());
        op.value(getLogicalSwitchSchema(), toOvsdb(entry.logicalSwitchId()));
        op.value(getIpaddrSchema(), entry.ipString());
        op.value(getLocationIdSchema(), toOvsdb(entry.locationId()));
        return new OvsdbInsert(op);
    }

    /**
     * Generate a delete operation matching mac, logical switch and ip of the
     * entry.
     */
    public Table.OvsdbDelete delete(MacEntry entry) {
        Delete<GenericTableSchema> op = new Delete<>(tableSchema);
        op.where(getMacMatcher(entry.mac()));
        op.where(getLogicalSwitchMatcher(entry.logicalSwitchId()));
        op.where(getIpaddrMatcher(entry.ip()));
        return new OvsdbDelete(op);
    }

    /**
     * Generate a delete operation matching logical switch
     */
    public Table.OvsdbDelete deleteByLogicalSwitchId(java.util.UUID lsId) {
        Delete<GenericTableSchema> op = new Delete<>(tableSchema);
        op.where(getLogicalSwitchMatcher(lsId));
        return new OvsdbDelete(op);
    }

    /**
     * Generate a delete operation matching mac and logical switch
     * (all ip mappings)
     */
    public Table.OvsdbDelete deleteByMac(VtepMAC mac, java.util.UUID lsId) {
        Delete<GenericTableSchema> op = new Delete<>(tableSchema);
        op.where(getMacMatcher(mac));
        op.where(getLogicalSwitchMatcher(lsId));
        return new OvsdbDelete(op);
    }

    @Override
    public <E extends VtepEntry> Row<GenericTableSchema> generateRow(
        E entry) throws IllegalArgumentException {
        Row<GenericTableSchema> row = super.generateRow(entry);
        MacEntry data = (MacEntry)entry;
        addToRow(row, getMacSchema(), data.macString());
        addToRow(row, getLogicalSwitchSchema(), toOvsdb(data.logicalSwitchId()));
        addToRow(row, getIpaddrSchema(), data.ipString());
        addToRow(row, getLocationIdSchema(), toOvsdb(data.locationId()));
        return row;
    }
}
