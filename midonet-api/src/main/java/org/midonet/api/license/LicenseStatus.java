/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.license;

import java.net.URI;
import javax.validation.constraints.NotNull;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;

public class LicenseStatus extends UriResource {
    private boolean valid;
    @NotNull
    private String message;

    public LicenseStatus() { }

    public LicenseStatus(boolean valid, String message) {
        this.valid = valid;
        this.message = message;
    }

    public boolean getValid() {
        return valid;
    }

    public String getMessage() {
        return message;
    }

    /**
     * @return URI of the resource.
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getLicenseStatus(getBaseUri());
        } else {
            return null;
        }
    }
}
