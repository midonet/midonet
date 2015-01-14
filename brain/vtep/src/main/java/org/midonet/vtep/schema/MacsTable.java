package org.midonet.vtep.schema;

import java.util.List;

import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.packets.IPv4Addr;

/**
 * Common schema for the {Ucast|Mcast}Mac{Local|Remote} tables
 */
public class MacsTable extends Table {
    static private final String COL_MAC = "MAC";
    static private final String COL_LOCATOR = "locator";
    static private final String COL_LOGICAL_SWITCH = "logical_switch";
    static private final String COL_IPADDR = "ipaddr";

    public MacsTable(DatabaseSchema databaseSchema,
                     GenericTableSchema tableSchema) {
        super(databaseSchema, tableSchema);
    }

    /** Get the schema of the columns of this table */
    public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols = super.getColumnSchemas();
        cols.add(getMacSchema());
        cols.add(getLocatorSchema());
        cols.add(getLogicalSwitchSchema());
        cols.add(getIpaddrSchema());
        return cols;
    }

    /** Get the schema for the MAC column */
    public ColumnSchema<GenericTableSchema, String> getMacSchema() {
        return tableSchema.column(COL_MAC, String.class);
    }

    /** Get the schema for the locator column */
    public ColumnSchema<GenericTableSchema, UUID> getLocatorSchema() {
        return tableSchema.column(COL_LOCATOR, UUID.class);
    }

    /** Get the schema for the logical switch column */
    public ColumnSchema<GenericTableSchema, UUID> getLogicalSwitchSchema() {
        return tableSchema.column(COL_LOGICAL_SWITCH, UUID.class);
    }

    /** Get the schema for the ipaddr column */
    public ColumnSchema<GenericTableSchema, String> getIpaddrSchema() {
        return tableSchema.column(COL_IPADDR, String.class);
    }

    /**
     * Extract the MAC from a table row, returning null if not set or empty
     */
    public String parseMac(Row<GenericTableSchema> row) {
        String value = (row == null)? null:
                       row.getColumn(getMacSchema()).getData();
        return (value == null || value.isEmpty())? null: value;
    }

    /**
     * Extract the logical switch internal id, to be used as a key for the
     * Logical_Switch table.
     */
    public UUID parseLogicalSwitch(Row<GenericTableSchema> row) {
        return (row == null)? null:
               row.getColumn(getLogicalSwitchSchema()).getData();
    }

    /**
     * Extract the IpAddr associated to the MAC, returning null if not set or
     * empty
     */
    public IPv4Addr parseIpaddr(Row<GenericTableSchema> row) {
        String value = (row == null)? null:
                       row.getColumn(getIpaddrSchema()).getData();
        return (value == null || value.isEmpty())? null:
               IPv4Addr.fromString(value);
    }

}
