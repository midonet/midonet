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
        super(databaseSchema, TB_NAME);
    }

    /** Get the schema of the columns of this table */
    @Override
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
    protected ColumnSchema<GenericTableSchema, String> getNameSchema() {
        return tableSchema.column(COL_NAME, String.class);
    }

    /** Get the schema for the description of the physical switch */
    protected ColumnSchema<GenericTableSchema, String> getDescriptionSchema() {
        return tableSchema.column(COL_DESCRIPTION, String.class);
    }

    /** Get the schema for the list of internal port ids */
    protected ColumnSchema<GenericTableSchema, Set> getPortsSchema() {
        return tableSchema.column(COL_PORTS, Set.class);
    }

    /** Get the schema for the list of management ips */
    protected ColumnSchema<GenericTableSchema, Set> getManagementIpsSchema() {
        return tableSchema.column(COL_MANAGEMENT_IPS, Set.class);
    }

    /** Get the schema for the list of tunnel ips */
    protected ColumnSchema<GenericTableSchema, Set> getTunnelIpsSchema() {
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
        return extractString(row, getNameSchema());
    }

    /**
     * Extract the physical switch description
     */
    public String parseDescription(Row<GenericTableSchema> row) {
        return extractString(row, getDescriptionSchema());
    }

    /**
     * Extract the set of physical port names
     */
    public Set<UUID> parsePorts(Row<GenericTableSchema> row) {
        return fromOvsdb(extractSet(row, getPortsSchema()));
    }

    /**
     * Extract the set of management ips
     */
    public Set<IPv4Addr> parseManagementIps(Row<GenericTableSchema> row) {
        return fromOvsdbIpSet(extractSet(row, getManagementIpsSchema()));
    }

    /**
     * Extract the set of tunnel ips (may be empty)
     */
    public Set<IPv4Addr> parseTunnelIps(Row<GenericTableSchema> row) {
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
        if (!clazz.isAssignableFrom(PhysicalSwitch.class))
            throw new IllegalArgumentException("wrong entry type " + clazz +
                                               " for table " + this.getClass());
        return (E)PhysicalSwitch.apply(parseUuid(row), parseName(row),
                                      parseDescription(row), parsePorts(row),
                                      parseManagementIps(row),
                                      parseTunnelIps(row));
    }

    /**
     * Insertion of physical port information
     */
    @Override
    public <E extends VtepEntry> OvsdbInsert insert(E row)
        throws IllegalArgumentException {
        if (!PhysicalSwitch.class.isAssignableFrom(row.getClass()))
            throw new IllegalArgumentException("wrong entry type " +
                                               row.getClass() +
                                               " for table " + this.getClass());
        PhysicalSwitch ps = (PhysicalSwitch)row;
        Insert<GenericTableSchema> op = super.insert(ps.uuid());
        op.value(getNameSchema(), ps.name());
        op.value(getDescriptionSchema(), ps.description());
        op.value(getPortsSchema(), toOvsdb(ps.ports()));
        op.value(getManagementIpsSchema(), toOvsdbIpSet(ps.mgmtIps()));
        op.value(getTunnelIpsSchema(), toOvsdbIpSet(ps.tunnelIps()));
        return new OvsdbInsert(op);
    }

}
