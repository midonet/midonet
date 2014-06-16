/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.license;

import java.util.UUID;

import net.java.truelicense.core.LicenseManagementException;

/**
 * An exception thrown when the user installs a license that already exists.
 */
public class LicenseExistsException extends LicenseManagementException {
    private final UUID licenseId;
    private final String message;

    public LicenseExistsException(UUID licenseId, String message) {
        this.licenseId = licenseId;
        this.message = message;
    }

    public UUID getLicenseId() {
        return licenseId;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
