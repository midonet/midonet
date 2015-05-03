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
import java.util.Set;

import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.cluster.data.vtep.model.PhysicalLocatorSet;
import org.midonet.cluster.data.vtep.model.VtepEntry;

import static org.midonet.vtep.OvsdbTranslator.fromOvsdb;
import static org.midonet.vtep.OvsdbTranslator.toOvsdb;

/**
 * Schema for the Ovsdb physical switch table
 */
public final class PhysicalLocatorSetTable extends Table {
    static public final String TB_NAME = "Physical_Locator_Set";
    static private final String COL_LOCATORS = "locators";

    public PhysicalLocatorSetTable(DatabaseSchema databaseSchema) {
        super(databaseSchema, TB_NAME, PhysicalLocatorSet.class);
    }

    public String getName() {
        return TB_NAME;
    }

    /** Get the schema of the columns of this table */
    @Override
    public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols =
            super.partialColumnSchemas();
        cols.add(getLocatorsSchema());
        return cols;
    }

    /** Get the schema for the set of the physical locators */
    private ColumnSchema<GenericTableSchema, Set> getLocatorsSchema() {
        return tableSchema.column(COL_LOCATORS, Set.class);
    }

    /** Generate a matcher condition for a locator UUID */
    static public Condition getLocatorMatcher(java.util.UUID locId) {
        return new Condition(COL_LOCATORS, Function.INCLUDES, toOvsdb(locId));
    }

    /**
     * Extract the set of locator ids
     */
    private Set<java.util.UUID> parseLocators(Row<GenericTableSchema> row) {
        return fromOvsdb(extractSet(row, getLocatorsSchema()));
    }

    /**
     * Extract the locator set object
     */
    @Override
    @SuppressWarnings(value = "unckecked")
    public <E extends VtepEntry>
    E parseEntry(Row<GenericTableSchema> row, Class<E> clazz)
        throws IllegalArgumentException {
        ensureOutputClass(clazz);
        return (row == null)? null:
               (E)PhysicalLocatorSet.apply(parseUuid(row), parseLocators(row));
    }

    /**
     * Generate an insert command
     */
    @Override
    public <E extends VtepEntry> Table.OvsdbInsert insert(E row)
        throws IllegalArgumentException {
        Insert<GenericTableSchema> op = newInsert(row);
        PhysicalLocatorSet set = (PhysicalLocatorSet)row;
        op.value(getLocatorsSchema(), toOvsdb(set.locatorIds()));
        return new OvsdbInsert(op);
    }

    @Override
    public <E extends VtepEntry> Row<GenericTableSchema> generateRow(
        E entry) throws IllegalArgumentException {
        Row<GenericTableSchema> row = super.generateRow(entry);
        PhysicalLocatorSet data = (PhysicalLocatorSet)entry;
        addToRow(row, getLocatorsSchema(), toOvsdb(data.locatorIds()));
        return row;
    }
}
