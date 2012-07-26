/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midostore;

import java.util.UUID;

/**
* // TODO: mtoader ! Please explain yourself.
*/
public interface LocalStateBuilder {

    LocalStateBuilder setDatapathName(String datapathName);

    LocalStateBuilder addLocalPortInterface(UUID portId, String interfaceName);

    LocalStateBuilder removeLocalPortInterface(UUID portId, String interfaceName);

    void build();
}
