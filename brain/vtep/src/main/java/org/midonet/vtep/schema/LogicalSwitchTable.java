package org.midonet.vtep.schema;

import java.util.List;
import java.util.Set;

import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.vtep.model.LogicalSwitch;

/**
 * Schema for the Ovsdb logical switch table
 */
public class LogicalSwitchTable extends Table {
    static protected final String COL_NAME = "name";
    static protected final String COL_DESCRIPTION = "description";
    static protected final String COL_TUNNEL_KEY = "tunnel_key";

    public LogicalSwitchTable(DatabaseSchema databaseSchema,
                              GenericTableSchema tableSchema) {
        super(databaseSchema, tableSchema);
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
    public ColumnSchema<GenericTableSchema, String> getNameSchema() {
        return tableSchema.column(COL_NAME, String.class);
    }

    /** Get the schema for the description of the logical switch */
    public ColumnSchema<GenericTableSchema, String> getDescriptionSchema() {
        return tableSchema.column(COL_DESCRIPTION, String.class);
    }

    /** Get the schema for the optional tunnel key (vxlan vni) */
    public ColumnSchema<GenericTableSchema, Set> getTunnelKeySchema() {
        return tableSchema.column(COL_TUNNEL_KEY, Set.class);
    }

    /**
     * Extract the physical switch name, returning null if not set or empty
     */
    public String parseName(Row<GenericTableSchema> row) {
        String value = (row == null)? null:
                       row.getColumn(getNameSchema()).getData();
        return (value == null || value.isEmpty())? null: value;
    }

    /**
     * Extract the physical switch description
     */
    public String parseDescription(Row<GenericTableSchema> row) {
        String value = (row == null)? null:
                       row.getColumn(getDescriptionSchema()).getData();
        return (value == null)? "": value;
    }
    /**
     * Extract the tunnel key, returning null if not set
     */
    public Integer parseTunnelKey(Row<GenericTableSchema> row) {
        Set<Long> tunnelKey = (row == null)? null:
            (Set<Long>)row.getColumn(getTunnelKeySchema()).getData();
        return (tunnelKey == null || tunnelKey.isEmpty())? null:
               tunnelKey.iterator().next().intValue();
    }

    /**
     * Extract a complete logical switch from a table row
     */
    public LogicalSwitch parseLogicalSwitch(Row<GenericTableSchema> row) {
        return new LogicalSwitch(parseUuid(row),
                                 parseDescription(row), parseName(row),
                                 parseTunnelKey(row));
    }

}
