/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth;

import java.util.List;

/**
 * Interface defines methods to manage {@link Tenant} object list.
 */
public interface TenantList {

    public List<? extends Tenant> get();

}
