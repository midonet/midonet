package org.midonet.midolman.state.zkManagers;

/*
 * Enum identifying resource type for string building. This cuts down on the
 * amount of code necessary by allowing us to identify the resource in
 * function arguments, instead of making a new function for each resource type.
 */
public enum ResourceType {
    PORT("port"),
    RULE("rule"),
    ROUTER("router"),
    BRIDGE("bridge"),
    ROUTE("route"),
    CHAIN("chain");

    private final String name;
    ResourceType(String name) {
        this.name = name;
    }
    public String toString() {
        return name;
    }
}