package org.midonet.vtep.schema;

import java.util.List;
import java.util.Map;

import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.packets.IPv4Addr;
import org.midonet.vtep.OvsdbUtil;
import org.midonet.vtep.model.PhysicalLocator;

/**
 * Schema for the Ovsdb physical locator table
 */
public class PhysicalLocatorTable extends Table {
    static protected final String COL_ENCAPSULATION = "encapsulation_type";
    static protected final String COL_DST_IP = "dst_ip";
    static protected final String COL_BFD = "bfd";
    static protected final String COL_BFD_STATUS = "bfd_status";

    static private final String ENCAPSULATION_TYPE = "vxlan_over_ipv4";

    public PhysicalLocatorTable(DatabaseSchema databaseSchema,
                                GenericTableSchema tableSchema) {
        super(databaseSchema, tableSchema);
    }

    /** Get the schema of the columns of this table */
    public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols = super.getColumnSchemas();
        cols.add(getEncapsulationSchema());
        cols.add(getDstIpSchema());
        cols.add(getBfdSchema());
        cols.add(getBfdStatusSchema());
        return cols;
    }

    /** Get the schema for the name of the physical switch (id) */
    public ColumnSchema<GenericTableSchema, String> getEncapsulationSchema() {
        return tableSchema.column(COL_ENCAPSULATION, String.class);
    }

    /** Get the schema for the description of the physical switch */
    public ColumnSchema<GenericTableSchema, String> getDstIpSchema() {
        return tableSchema.column(COL_DST_IP, String.class);
    }

    /** Get the schema for the list of internal port ids */
    public ColumnSchema<GenericTableSchema, Map> getBfdSchema() {
        return tableSchema.column(COL_BFD, Map.class);
    }

    /** Get the schema for the list of management ips */
    public ColumnSchema<GenericTableSchema, Map> getBfdStatusSchema() {
        return tableSchema.column(COL_BFD_STATUS, Map.class);
    }

    /** Generate a matcher condition for the destination ip */
    public Condition getDstIpMatcher(IPv4Addr value) {
        return new Condition(COL_DST_IP, Function.EQUALS, value.toString());
    }

    /**
     * Extract the encapsulation type, returning null if not set or empty
     */
    public String parseEncapsulation(Row<GenericTableSchema> row) {
        String value = (row == null)? null:
                       row.getColumn(getEncapsulationSchema()).getData();
        return (value == null || value.isEmpty())? null: value;
    }

    /**
     * Extract the destination ip address
     */
    public IPv4Addr parseDstIp(Row<GenericTableSchema> row) {
        String value = (row == null)? null:
                       row.getColumn(getDstIpSchema()).getData();
        return (value == null || value.isEmpty())? null
                                                 : IPv4Addr.fromString(value);
    }

    /**
     * Extract the physical switch information from the table entry
     */
    public PhysicalLocator parsePhysicalLocator(Row<GenericTableSchema> row) {
        return new PhysicalLocator(parseUuid(row), parseEncapsulation(row),
                                   parseDstIp(row));
    }

    /**
     * Generate an insert command
     */
    public Insert<GenericTableSchema> insert(IPv4Addr ip) {
        Insert<GenericTableSchema> op = super.insert();
        op.value(getEncapsulationSchema(), ENCAPSULATION_TYPE);
        op.value(getDstIpSchema(), ip.toString());
        return op;
    }
}
