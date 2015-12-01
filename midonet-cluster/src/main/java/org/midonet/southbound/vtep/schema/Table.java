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

package org.midonet.southbound.vtep.schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.notation.Column;
import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.operations.Delete;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.operations.Operation;
import org.opendaylight.ovsdb.lib.operations.Select;
import org.opendaylight.ovsdb.lib.operations.Update;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.cluster.data.vtep.model.VtepEntry;
import org.midonet.southbound.vtep.OvsdbUtil;

import static org.midonet.southbound.vtep.OvsdbUtil.fromOvsdb;
import static org.midonet.southbound.vtep.OvsdbUtil.toOvsdb;

/**
 * Generic ovsdb table schema. This class (and its subclasses) aims
 * to hide as much as possible the internal representation of the
 * values inside the low-level ovsdb table implementation and expose
 * generic model representations.
 */
public abstract class Table<E extends VtepEntry> {
    static private final String COL_UUID = "_uuid";
    static private final String COL_VERSION = "_version";

    // Wrappers for scala interaction
    static public class OvsdbOperation {
        public final Operation<GenericTableSchema> op;
        public OvsdbOperation(Operation<GenericTableSchema> op) {
            this.op = op;
        }
    }
    static public class OvsdbInsert extends OvsdbOperation {
        public OvsdbInsert(Insert<GenericTableSchema> op) {
            super(op);
        }
    }
    static public class OvsdbDelete extends OvsdbOperation {
        public OvsdbDelete(Delete<GenericTableSchema> op) {
            super(op);
        }
    }
    static public class OvsdbUpdate extends OvsdbOperation {
        public OvsdbUpdate(Update<GenericTableSchema> op) {
            super(op);
        }
    }
    static public class OvsdbSelect extends OvsdbOperation {
        public OvsdbSelect(Select<GenericTableSchema> op) {
            super(op);
        }
    }

    private void checkColumnSchemas(String tblName) throws NoSuchElementException {
        for (ColumnSchema<GenericTableSchema, ?> col : getMandatoryColumnSchemas()) {
            if (col == null)
                throw new NoSuchElementException(
                    "missing column in ovsdb table: " + tblName);
        }
    }

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
    protected final Class<? extends VtepEntry> entryClass;

    protected Table(DatabaseSchema databaseSchema, String tableName,
                    Class<? extends VtepEntry> entryClass) {
        this.databaseSchema = databaseSchema;
        this.tableSchema = getTblSchema(databaseSchema, tableName);
        this.entryClass = entryClass;
        checkColumnSchemas(tableName);
    }

    /** Get the table name */
    abstract public String getName();

    /** Get the database schema where this table is contained */
    public DatabaseSchema getDbSchema() {
        return databaseSchema;
    }

    /** Get the schema of this table */
    public GenericTableSchema getSchema() {
        return tableSchema;
    }

    /** Get the class of the entries of this table */
    public Class<? extends VtepEntry> getEntryClass() {
        return entryClass;
    }

    /** Check if the target entry class is compatible with this table */
    protected void ensureOutputClass(Class<?> clazz)
        throws IllegalArgumentException {
        if (!clazz.isAssignableFrom(entryClass))
            throw new IllegalArgumentException("wrong entry type " + clazz +
                                               " for table " + this.getName());
    }

    /** Check if the source entry class is compatible with this table */
    protected void ensureInputClass(Class<?> clazz)
        throws IllegalArgumentException {
        if (!entryClass.isAssignableFrom(clazz))
            throw new IllegalArgumentException("wrong entry type " + clazz +
                                               " for table " + this.getName());
    }

    /** Get the schema of the columns of this table */
    abstract public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas();

    /**
     * Get the schema of the columns of this table that MUST be present in
     * the schema in order for us to work with it.  This is so that we can
     * exclude optional columns from the schema check
     */
    public List<ColumnSchema<GenericTableSchema, ?>> getMandatoryColumnSchemas() {
        return this.getColumnSchemas();
    }

    protected List<ColumnSchema<GenericTableSchema, ?>> partialColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols = new ArrayList<>();
        cols.add(getUuidSchema());
        cols.add(getVersionSchema());
        return cols;
    }

    /** Get the schema for the generic uuid column (key) */
    protected ColumnSchema<GenericTableSchema, UUID> getUuidSchema() {
        return tableSchema.column(COL_UUID, UUID.class);
    }

    /** Get the schema for the generic version column */
    protected ColumnSchema<GenericTableSchema, UUID> getVersionSchema() {
        return tableSchema.column(COL_VERSION, UUID.class);
    }

    /** Generate a matcher condition for the key uuid (for use with select) */
    static public Condition getUuidMatcher(java.util.UUID value) {
        return new Condition(COL_UUID, Function.EQUALS, toOvsdb(value));
    }

    /**
     * Extract a string from a column
     */
    protected String extractString(
        Row<GenericTableSchema> row,
        ColumnSchema<GenericTableSchema, String> col) {
        String value = (row == null)? null: row.getColumn(col).getData();
        return (value == null || value.isEmpty())? null: value;
    }

    /**
     * Extract an uuid from a column
     */
    protected java.util.UUID extractUuid(
        Row<GenericTableSchema> row,
        ColumnSchema<GenericTableSchema, UUID> col) {
        return (row == null)? null: fromOvsdb(row.getColumn(col).getData());
    }

    /**
     * Extract sets, protecting against null values
     */
    protected Set extractSet(
        Row<GenericTableSchema> row,
        ColumnSchema<GenericTableSchema, Set> col) {
        if (row == null) {
            return new HashSet();
        } else {
            Set set = row.getColumn(col).getData();
            return (set == null)? new HashSet(): set;
        }
    }

    /**
     * Extract maps, protecting against null values
     */
    protected Map extractMap(
        Row<GenericTableSchema> row,
        ColumnSchema<GenericTableSchema, Map> col) {
        if (row == null) {
            return new HashMap();
        } else {
            Map map = row.getColumn(col).getData();
            return (map == null)? new HashMap(): map;
        }
    }

    /**
     * Extract the uuid of a particular table entry
     */
    public java.util.UUID parseUuid(Row<GenericTableSchema> row) {
        return extractUuid(row, getUuidSchema());
    }

    /**
     * Generate a select operation including all known columns
     */
    public OvsdbSelect selectAll() {
        Select<GenericTableSchema> op = new Select<>(getSchema());
        for (ColumnSchema<GenericTableSchema, ?> col: getColumnSchemas()) {
            op.column(col);
        }
        return new OvsdbSelect(op);
    }

    public OvsdbSelect selectById(java.util.UUID id) {
        Select<GenericTableSchema> op = new Select<>(getSchema());
        for (ColumnSchema<GenericTableSchema, ?> col: getColumnSchemas()) {
            op.column(col);
        }
        op.where(new Condition(COL_UUID, Function.EQUALS,
                               OvsdbUtil.toOvsdb(id)));
        return new OvsdbSelect(op);
    }

    /**
     * Generate an insert operation with the given id (or generate a new id,
     * if null)
     */
    protected Insert<GenericTableSchema> newInsert(VtepEntry entry, String id)
        throws IllegalArgumentException {
        ensureInputClass(entry.getClass());
        Insert<GenericTableSchema> op = new Insert<>(tableSchema);
        if (null != id) {
            op.withId(id);
        }
        return op;
    }

    /**
     * Generate an insert operation corresponding to the given VTEP table entry.
     */
    abstract public OvsdbInsert insert(E entry, String id);

    /**
     * Generates a delete operation corresponding to the given VTEP table entry.
     */
    abstract public OvsdbDelete delete(E entry);

    /**
     * Generate a delete operation for the given entry
     */
    final public OvsdbDelete deleteById(java.util.UUID id) {
        Delete<GenericTableSchema> op = new Delete<>(tableSchema);
        op.where(getUuidMatcher(id));
        return new OvsdbDelete(op);
    }

    /**
     * Generate a normalized vtep table entry from a row from this type of table
     */
    abstract public E parseEntry(Row<GenericTableSchema> row);

    /**
     * Generate a row from a set of columns expressed as a map
     * (column_name -> column_value). This is a convenience method
     * for some low-level ovsdb data manipulations.
     */
    @SuppressWarnings(value = "unchecked")
    public Row<GenericTableSchema> generateRow(Map<String, Object> rowValues) {
        Row<GenericTableSchema> row = new Row<>(tableSchema);
        for (ColumnSchema<GenericTableSchema, ?> c: getColumnSchemas()) {
            if (rowValues.containsKey(c.getName())) {
                row.addColumn(c.getName(),
                              new Column(c, rowValues.get(c.getName())));
            }
        }
        return row;
    }

    /**
     * Generate a row from a normalized vtep table entry
     */
    public Row<GenericTableSchema> generateRow(E entry)
        throws IllegalArgumentException {
        Row<GenericTableSchema> row = new Row<>(tableSchema);
        return addToRow(row, getUuidSchema(), toOvsdb(entry.uuid()));
    }

    /**
     * Update a row from a set of columns expressed as a map
     * (column_name -> column_value). This is a convenience method
     * for some low-level ovsdb data manipulations.
     */
    @SuppressWarnings(value = "unchecked")
    public Row<GenericTableSchema> updateRow(Row<GenericTableSchema> row,
                                             Map<String, Object> rowValues) {
        Row<GenericTableSchema> newRow = new Row<>(tableSchema);
        for (ColumnSchema<GenericTableSchema, ?> c: getColumnSchemas()) {
            if (rowValues.containsKey(c.getName())) {
                newRow.addColumn(c.getName(),
                              new Column(c, rowValues.get(c.getName())));
            } else if (row.getColumn(c) != null) {
                newRow.addColumn(c.getName(), row.getColumn(c));
            }
        }
        return newRow;
    }

    /**
     * Auxiliary method to add a column to a row
     */
    static protected <D> Row<GenericTableSchema> addToRow(
        Row<GenericTableSchema> row,
        ColumnSchema<GenericTableSchema, D> schema, D value) {
        row.addColumn(schema.getName(), new Column<>(schema, value));
        return row;
    }

    /**
     * Methods to circumvent ovsdb type issues when interacting with scala code
     */
    static public TableUpdates newTableUpdates(
        Map<String, TableUpdate<?>> tableUpdatesMap) {
        TableUpdates updates = new TableUpdates();
        for (Map.Entry<String, TableUpdate<?>> e: tableUpdatesMap.entrySet()) {
            updates.getUpdates().put(e.getKey(), (TableUpdate)e.getValue());
        }
        return updates;
    }

    public TableUpdates makeTableUpdates(TableUpdate<?> update) {
        Map<String, TableUpdate<?>> map = new HashMap<>();
        map.put(getName(), update);
        return newTableUpdates(map);
    }
}
