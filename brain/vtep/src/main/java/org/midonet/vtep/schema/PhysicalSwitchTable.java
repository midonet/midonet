package org.midonet.vtep.schema;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.packets.IPv4Addr;
import org.midonet.cluster.data.vtep.model.PhysicalSwitch;

/**
 * Schema for the Ovsdb physical switch table
 */
public final class PhysicalSwitchTable extends Table {
    static private final String TB_NAME = "Physical_Switch";
    static private final String COL_NAME = "name";
    static private final String COL_DESCRIPTION = "description";
    static private final String COL_PORTS = "ports";
    static private final String COL_MANAGEMENT_IPS = "management_ips";
    static private final String COL_TUNNEL_IPS = "tunnel_ips";

    public PhysicalSwitchTable(DatabaseSchema databaseSchema) {
        super(databaseSchema, TB_NAME);
    }

    /** Get the schema of the columns of this table */
    public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols = super.getColumnSchemas();
        cols.add(getNameSchema());
        cols.add(getDescriptionSchema());
        cols.add(getPortsSchema());
        cols.add(getManagementIpsSchema());
        cols.add(getTunnelIpsSchema());
        return cols;
    }

    /** Get the schema for the name of the physical switch (id) */
    public ColumnSchema<GenericTableSchema, String> getNameSchema() {
        return tableSchema.column(COL_NAME, String.class);
    }

    /** Get the schema for the description of the physical switch */
    public ColumnSchema<GenericTableSchema, String> getDescriptionSchema() {
        return tableSchema.column(COL_DESCRIPTION, String.class);
    }

    /** Get the schema for the list of internal port ids */
    public ColumnSchema<GenericTableSchema, Set> getPortsSchema() {
        return tableSchema.column(COL_PORTS, Set.class);
    }

    /** Get the schema for the list of management ips */
    public ColumnSchema<GenericTableSchema, Set> getManagementIpsSchema() {
        return tableSchema.column(COL_MANAGEMENT_IPS, Set.class);
    }

    /** Get the schema for the list of tunnel ips */
    public ColumnSchema<GenericTableSchema, Set> getTunnelIpsSchema() {
        return tableSchema.column(COL_TUNNEL_IPS, Set.class);
    }

    /** Generate a matcher condition for the management ips
     * (for use with select) */
    public Condition getManagementIpsMatcher(IPv4Addr value) {
        return new Condition(COL_MANAGEMENT_IPS, Function.INCLUDES,
                             value.toString());
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
     * Extract the set of physical port names
     */
    public Set<String> parsePorts(Row<GenericTableSchema> row) {
        return (row == null)? new HashSet<String>():
            (Set<String>)row.getColumn(getPortsSchema()).getData();
    }

    /**
     * Extract the set of management ips
     */
    public Set<IPv4Addr> parseManagementIps(Row<GenericTableSchema> row) {
        Set<IPv4Addr> ipAdresses = new HashSet<>();
        Set<String> ipStrings = (row == null)? null:
            (Set<String>)row.getColumn(getManagementIpsSchema()).getData();
        if (ipStrings != null) {
            for (String str: ipStrings) {
                if (!str.isEmpty())
                    ipAdresses.add(IPv4Addr.fromString(str));
            }
        }
        return ipAdresses;
    }

    /**
     * Extract the set of tunnel ips (may be empty)
     */
    public Set<IPv4Addr> parseTunnelIps(Row<GenericTableSchema> row) {
        Set<IPv4Addr> ipAdresses = new HashSet<>();
        Set<String> ipStrings = (row == null)? null:
            (Set<String>)row.getColumn(getTunnelIpsSchema()).getData();
        if (ipStrings != null) {
            for (String str: ipStrings) {
                if (!str.isEmpty())
                    ipAdresses.add(IPv4Addr.fromString(str));
            }
        }
        return ipAdresses;
    }

    /**
     * Extract the physical switch information from the table entry
     */
    public PhysicalSwitch parsePhysicalSwitch(Row<GenericTableSchema> row) {
        return new PhysicalSwitch(parseUuid(row), parseName(row),
                                  parseDescription(row), parsePorts(row),
                                  parseManagementIps(row), parseTunnelIps(row));
    }
}
