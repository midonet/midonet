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

    protected MacsTable(DatabaseSchema databaseSchema, String tableName) {
        super(databaseSchema, tableName);
    }

    abstract public Boolean isUcastTable();

    /** Get the schema of the columns of this table */
    public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols = super.getColumnSchemas();
        cols.add(getMacSchema());
        cols.add(getLogicalSwitchSchema());
        cols.add(getIpaddrSchema());
        return cols;
    }

    /** Get the schema for the MAC column */
    public ColumnSchema<GenericTableSchema, String> getMacSchema() {
        return tableSchema.column(COL_MAC, String.class);
    }

    /** Get the schema for the logical switch column */
    public ColumnSchema<GenericTableSchema, UUID> getLogicalSwitchSchema() {
        return tableSchema.column(COL_LOGICAL_SWITCH, UUID.class);
    }

    /** Get the schema for the ipaddr column */
    public ColumnSchema<GenericTableSchema, String> getIpaddrSchema() {
        return tableSchema.column(COL_IPADDR, String.class);
    }

    /** Get the schema for the location id column
     *  varies for ucast/mcast */
    abstract public ColumnSchema<GenericTableSchema, UUID> getLocationIdSchema();

    /** Generate a condition to match Mac address */
    public Condition getMacMatcher(VtepMAC mac) {
        return new Condition(COL_MAC, Function.EQUALS, mac.toString());
    }

    /** Generate a condition to match logical switch */
    public Condition getLogicalSwitchMatcher(java.util.UUID lsId) {
        return new Condition(COL_LOGICAL_SWITCH, Function.EQUALS, toOvsdb(lsId));
    }

    /** Generate a condition to match ip address */
    public Condition getIpaddrMatcher(IPv4Addr ip) {
        return new Condition(COL_IPADDR, Function.EQUALS,
                             (ip == null)? null: ip.toString());
    }

    /**
     * Extract the MAC from a table row, returning null if not set or empty
     */
    public VtepMAC parseMac(Row<GenericTableSchema> row) {
        String value = extractString(row, getMacSchema());
        return (value == null)? VtepMAC.UNKNOWN_DST(): VtepMAC.fromString(value);
    }

    /**
     * Extract the logical switch internal id, to be used as a key for the
     * Logical_Switch table.
     */
    public java.util.UUID parseLogicalSwitch(Row<GenericTableSchema> row) {
        return extractUuid(row, getLogicalSwitchSchema());
    }

    /**
     * Extract the IpAddr associated to the MAC, returning null if not set or
     * empty
     */
    public IPv4Addr parseIpaddr(Row<GenericTableSchema> row) {
        String value = extractString(row, getIpaddrSchema());
        return (value == null)? null: IPv4Addr.fromString(value);
    }

    /**
     * Extract the mac entry from the row
     */
    abstract public MacEntry parseMacEntry(Row<GenericTableSchema> row);

    /**
     * Generate an insert operation
     */
    public Insert<GenericTableSchema> insert(MacEntry entry) {
        java.util.UUID rowId = (entry.uuid() == null)?
                               entryToId(entry.mac(), entry.ip()): entry.uuid();
        Insert<GenericTableSchema> op = super.insert(rowId);
        op.value(getMacSchema(), entry.macString());
        op.value(getLogicalSwitchSchema(), toOvsdb(entry.logicalSwitchId()));
        op.value(getIpaddrSchema(), entry.ipString());
        op.value(getLocationIdSchema(), toOvsdb(entry.locationId()));
        return op;
    }

    /**
     * Generate a delete operation matching mac, logical switch and ip of the
     * entry.
     */
    public Delete<GenericTableSchema> delete(MacEntry entry) {
        Delete<GenericTableSchema> op = new Delete<>(tableSchema);
        op.where(getMacMatcher(entry.mac()));
        op.where(getLogicalSwitchMatcher(entry.logicalSwitchId()));
        op.where(getIpaddrMatcher(entry.ip()));
        return op;
    }

    static protected java.util.UUID entryToId(VtepMAC mac, IPv4Addr macIp) {
        // A trick to guarantee that we only insert one row per
        // mac-ip combination (assuming nobody else inserts rows...)
        return new java.util.UUID(
            (mac == null || mac.mac() == null) ? 0 : mac.mac().asLong(),
            (macIp == null) ? 0 : macIp.addr());
    }
}
