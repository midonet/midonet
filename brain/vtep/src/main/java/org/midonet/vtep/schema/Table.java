package org.midonet.vtep.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.operations.Select;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import static org.midonet.vtep.OvsdbUtil.fromOvsdbUUID;
import static org.midonet.vtep.OvsdbUtil.toOvsdbUUID;

/**
 * Generic ovsdb table schema
 */
public abstract class Table {
    static private final String COL_UUID = "_uuid";
    static private final String COL_VERSION = "_version";

    /** Retrieve the table schema from the containing database schema */
    static private GenericTableSchema getTblSchema(DatabaseSchema dbs,
                                                   String tblName)
        throws NoSuchElementException {
        GenericTableSchema tbl = dbs.table(tblName, GenericTableSchema.class);
        if (tbl == null)
            throw new NoSuchElementException("missing ovsdb table: " + tblName);
        return tbl;
    }

    protected final DatabaseSchema databaseSchema;
    protected final GenericTableSchema tableSchema;

    protected Table(DatabaseSchema databaseSchema, String tableName) {
        this.databaseSchema = databaseSchema;
        this.tableSchema = getTblSchema(databaseSchema, tableName);
    }

    /** Get the database schema where this table is contained */
    public DatabaseSchema getDbSchema() {
        return databaseSchema;
    }

    /** Get the schema of this table */
    public GenericTableSchema getSchema() {
        return tableSchema;
    }

    /** Get the schema of the columns of this table */
    public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols = new ArrayList<>();
        cols.add(getUuidSchema());
        cols.add(getVersionSchema());
        return cols;
    }

    /** Get the schema for the generic uuid column (key) */
    public ColumnSchema<GenericTableSchema, UUID> getUuidSchema() {
        return tableSchema.column(COL_UUID, UUID.class);
    }

    /** Get the schema for the generic version column */
    public ColumnSchema<GenericTableSchema, UUID> getVersionSchema() {
        return tableSchema.column(COL_VERSION, UUID.class);
    }

    /** Generate a matcher condition for the key uuid (for use with select) */
    public Condition getUuidMatcher(java.util.UUID value) {
        return new Condition(COL_UUID, Function.EQUALS, toOvsdbUUID(value));
    }

    /**
     * Extract the uuid of a particular table entry
     */
    public java.util.UUID parseUuid(Row<GenericTableSchema> row) {
        return (row == null)? null: fromOvsdbUUID(
            row.getColumn(getUuidSchema()).getData());
    }

    /**
     * Generate a select operation including all known columns
     */
    public Select<GenericTableSchema> selectAll() {
        Select<GenericTableSchema> op = new Select<>(getSchema());
        for (ColumnSchema<GenericTableSchema, ?> col: getColumnSchemas()) {
            op.column(col);
        }
        return op;
    }

    /**
     * Generate an insert operation with a random id
     */
    public Insert<GenericTableSchema> insert(java.util.UUID rowId) {
        Insert<GenericTableSchema> op = new Insert<>(tableSchema);
        op.setUuidName(COL_UUID);
        op.setUuid(rowId.toString());
        op.value(getUuidSchema(), toOvsdbUUID(rowId));
        return op;
    }

    public Insert<GenericTableSchema> insert() {
        return insert(java.util.UUID.randomUUID());
    }
}
