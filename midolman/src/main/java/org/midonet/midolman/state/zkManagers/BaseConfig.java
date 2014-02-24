/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.state.zkManagers;

import org.codehaus.jackson.annotate.JsonIgnore;

import java.util.UUID;

public abstract class BaseConfig {
    @JsonIgnore
    public transient UUID id = null;
}
