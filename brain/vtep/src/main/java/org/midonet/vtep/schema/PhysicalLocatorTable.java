package org.midonet.vtep.schema;

import java.util.List;
import java.util.Map;

import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.packets.IPv4Addr;
import org.midonet.cluster.data.vtep.model.PhysicalLocator;

/**
 * Schema for the Ovsdb physical locator table
 */
public final class PhysicalLocatorTable extends Table {
    static private final String TB_NAME = "Physical_Locator";
    static private final String COL_ENCAPSULATION = "encapsulation_type";
    static private final String COL_DST_IP = "dst_ip";
    static private final String COL_BFD = "bfd";
    static private final String COL_BFD_STATUS = "bfd_status";

    public PhysicalLocatorTable(DatabaseSchema databaseSchema) {
        super(databaseSchema, TB_NAME);
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

    /** Get the schema for the encapsulation type */
    protected ColumnSchema<GenericTableSchema, String> getEncapsulationSchema() {
        return tableSchema.column(COL_ENCAPSULATION, String.class);
    }

    /** Get the schema for the tunnel destination ip */
    protected ColumnSchema<GenericTableSchema, String> getDstIpSchema() {
        return tableSchema.column(COL_DST_IP, String.class);
    }

    /** Get the schema for BFD */
    protected ColumnSchema<GenericTableSchema, Map> getBfdSchema() {
        return tableSchema.column(COL_BFD, Map.class);
    }

    /** Get the schema for BFD status */
    protected ColumnSchema<GenericTableSchema, Map> getBfdStatusSchema() {
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
        return extractString(row, getEncapsulationSchema());
    }

    /**
     * Extract the destination ip address
     */
    public IPv4Addr parseDstIp(Row<GenericTableSchema> row) {
        String value = extractString(row, getDstIpSchema());
        return (value == null)? null : IPv4Addr.fromString(value);
    }

    /**
     * Extract the physical switch information from the table entry
     */
    public PhysicalLocator parsePhysicalLocator(Row<GenericTableSchema> row) {
        return new PhysicalLocator(parseUuid(row), parseDstIp(row),
                                   parseEncapsulation(row));
    }

    /**
     * Generate an insert command
     */
    public Insert<GenericTableSchema> insert(PhysicalLocator loc) {
        Insert<GenericTableSchema> op = super.insert(loc.uuid());
        op.value(getEncapsulationSchema(), loc.encapsulation());
        op.value(getDstIpSchema(), loc.dstIpString());
        return op;
    }
}
