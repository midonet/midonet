/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.host.commands.executors;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.midonet.midolman.host.state.HostDirectory;

public enum CommandProperty {
    mtu("mtu") {{
        typedAs(Integer.class,
                new TypeValidator.IntTypeValidator(),
                MtuCommandExecutor.class);
    }},

    address("address") {{
        typedAs(String.class,
                new TypeValidator.InetAddressValidator(),
                AddressCommandExecutor.class);
    }},

    mac("mac") {{
        typedAs(String.class,
                new TypeValidator.StringValidator(),
                MacCommandExecutor.class);
    }},

    iface("interface") {{
        typedAs(HostDirectory.Interface.Type.class,
                new TypeValidator.InterfaceTypeValidator(),
                InterfaceCommandExecutor.class);
    }};

    private String key;
    private Class<?> type;
    private Class<? extends CommandExecutor<?>> executor;
    private TypeValidator<?> validator;

    private static final Map<String, CommandProperty> lookupByKey =
        new HashMap<String, CommandProperty>();

    static {
        for (CommandProperty commandProperty : CommandProperty.values()) {
            lookupByKey.put(commandProperty.getKey(), commandProperty);
        }
    }

    private CommandProperty(String key) {
        setKey(key);
    }

    protected <T> void typedAs(Class<T> type,
                               TypeValidator<T> validator,
                               Class<? extends CommandExecutor<T>> executor) {
        setType(type);
        setExecutor(executor);
        setValidator(validator);
    }

    public String getKey() {
        return key;
    }

    private void setKey(String key) {
        this.key = key;
    }

    public Class<?> getType() {
        return type;
    }

    private void setType(Class<?> type) {
        this.type = type;
    }

    public Class<? extends CommandExecutor<?>> getExecutor() {
        return executor;
    }

    private void setExecutor(Class<? extends CommandExecutor<?>> executor) {
        this.executor = executor;
    }

    public TypeValidator<?> getValidator() {
        return validator;
    }

    private void setValidator(TypeValidator<?> validator) {
        this.validator = validator;
    }

    public static CommandProperty getByKey(String key) {
        return lookupByKey.get(key);
    }
}
