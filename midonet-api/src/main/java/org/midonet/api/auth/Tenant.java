/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth;

/**
 * Interface representing a tenant object in the identity service.  This is the
 * generic MidoNet representation of tenant models from various identity
 * services that it could integrate with..
 */
public interface Tenant {

    String getId();

    String getName();
}
