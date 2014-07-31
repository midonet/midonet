/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.tools.ovsdb.cli;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nonnull;

import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.ovsdb.lib.notation.UUID;

/**
 * A class that maintains a static list of converters, to convert the
 * command line argument types and values to the corresponding Java class
 * and object pair.
 *
 * Each converter entry also requires a custom converter object (that implements
 * the Converter interface) to transform the value string to a value object
 * of the argument type.
 */
public final class OvsDbConverter {

    /**
     * The interface for argument converter.
     */
    public interface Converter {
        Object convert(String value);
    }

    /**
     * A custom converter to string.
     */
    public static class StringConverter implements Converter {
        @Override
        public Object convert(String value) {
            return value;
        }
    }

    /**
     * A custom converter to integer.
     */
    public static class IntegerConverter implements Converter {
        @Override
        public Object convert(String value) {
            return Integer.parseInt(value);
        }
    }

    /**
     * A custom converter to OVS-DB UUID.
     */
    public static class UuidConverter implements Converter {
        @Override
        public Object convert(String value) {
            return new UUID(value);
        }
    }

    /**
     * A converter entry, consisting of the Java type, command-line name, and
     * converter.
     */
    public static final class Entry {
        public final Class<?> type;
        public final String name;
        public final Converter converter;

        public Entry(@Nonnull Class<?> type, @Nonnull String name,
                     Converter converter) {
            this.type = type;
            this.name = name;
            this.converter = converter;
        }

        /**
         * Indicates if this entry has a converter.
         */
        public boolean hasConverter() {
            return null != converter;
        }

        /**
         * Converts the specified string value to an object of the class type
         * for the current converter entry.
         * @param value The value.
         * @return The object.
         */
        public Object convert(String value) {
            return null != converter ? converter.convert(value) : null;
        }
    }

    private static final Map<Class<?>, Entry> entriesByType = new HashMap<>();
    private static final Map<String, Entry> entriesByName = new HashMap<>();

    static {
        // Register default converters.
        register(String.class, "string", new StringConverter());
        register(int.class, "int", new IntegerConverter());
        register(int.class, "uuid", new UuidConverter());
        register(Node.class, "node", null);
    }

    /**
     * Registers a converter in the converters list.
     * @param type The Java class type.
     * @param name The command-line name.
     * @param converter The converter.
     * @return True if the converter was registered successfully, false
     *         otherwise.
     */
    public static boolean register(Class<?> type, String name,
                                   Converter converter) {
        if (entriesByType.containsKey(type))
            return false;
        if (entriesByName.containsKey(name))
            return false;

        Entry entry = new Entry(type, name, converter);

        entriesByType.put(type, entry);
        entriesByName.put(name, entry);

        return true;
    }

    /**
     * Gets the converter entry for the specified name.
     * @param name The command-line name.
     * @return The converter entry.
     */
    public static Entry get(String name) {
        return entriesByName.get(name);
    }

    /**
     * Gets the converter entry for the specified type.
     * @param type The Java class type.
     * @return The converter entry.
     */
    public static Entry get(Class<?> type) {
        return entriesByType.get(type);
    }
}
