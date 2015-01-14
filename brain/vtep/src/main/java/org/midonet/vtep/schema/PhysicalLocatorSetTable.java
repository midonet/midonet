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
    static private final String TB_NAME = "Physical_Locator_Set";
    static private final String COL_LOCATORS = "locators";

    public PhysicalLocatorSetTable(DatabaseSchema databaseSchema) {
        super(databaseSchema, TB_NAME);
    }

    /** Get the schema of the columns of this table */
    @Override
    public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols = super.getColumnSchemas();
        cols.add(getLocatorsSchema());
        return cols;
    }

    /** Get the schema for the set of the physical locators */
    protected ColumnSchema<GenericTableSchema, Set> getLocatorsSchema() {
        return tableSchema.column(COL_LOCATORS, Set.class);
    }

    /** Generate a matcher condition for a locator UUID */
    public Condition getLocatorMatcher(java.util.UUID locId) {
        return new Condition(COL_LOCATORS, Function.INCLUDES, toOvsdb(locId));
    }

    /**
     * Extract the set of locator ids
     */
    public Set<java.util.UUID> parseLocators(Row<GenericTableSchema> row) {
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
        if (!clazz.isAssignableFrom(PhysicalLocatorSet.class))
            throw new IllegalArgumentException("wrong entry type " + clazz +
                                               " for table " + this.getClass());
        return (E)PhysicalLocatorSet.apply(parseUuid(row), parseLocators(row));
    }

    /**
     * Generate an insert command
     */
    @Override
    public <E extends VtepEntry> OvsdbInsert insert(E row)
        throws IllegalArgumentException {
        if (!PhysicalLocatorSet.class.isAssignableFrom(row.getClass()))
            throw new IllegalArgumentException("wrong entry type " +
                                               row.getClass() +
                                               " for table " + this.getClass());
        PhysicalLocatorSet set = (PhysicalLocatorSet)row;
        Insert<GenericTableSchema> op = super.insert(set.uuid());
        op.value(getLocatorsSchema(), toOvsdb(set.locatorIds()));
        return new OvsdbInsert(op);
    }
}
