/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.state;

/**
 * Status property used in MidoNet resources. They mirror statuses defined in
 * Neutron.
 */
public enum PoolHealthMonitorMappingStatus {
    ACTIVE, INACTIVE, PENDING_CREATE, PENDING_UPDATE, PENDING_DELETE, ERROR;
}
