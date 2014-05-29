/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.client.dto;

/**
 * Status property used in MidoNet DTOs. They mirror statuses defined in
 * Neutron.
 */
public enum PoolHealthMonitorMappingStatus {
    ACTIVE, INACTIVE, PENDING_CREATE, PENDING_UPDATE,
    PENDING_DELETE, ERROR
}
