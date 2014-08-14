/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.license;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.UUID;

import net.java.truelicense.core.License;

public class LicenseInformation implements Serializable {
    private static final long serialVersionUID = 1L;

    private final License license;
    private UUID licenseId;
    private UUID productId;
    private String productName;
    private int majorVersion;
    private int minorVersion;
    private int agentQuota;

    public LicenseInformation() {
        license = null;
    }

    public LicenseInformation(License license, UUID licenseId, UUID productId,
                              String productName, int majorVersion,
                              int minorVersion, int agentQuota) {
        this.license = license;
        this.licenseId = licenseId;
        this.productId = productId;
        this.productName = productName;
        this.majorVersion = majorVersion;
        this.minorVersion = minorVersion;
        this.agentQuota = agentQuota;
    }

    public License getLicense() {
        return license;
    }

    public UUID getLicenseId() {
        return licenseId;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public int getMajorVersion() {
        return majorVersion;
    }

    public int getMinorVersion() {
        return minorVersion;
    }

    public int getAgentQuota() {
        return agentQuota;
    }

    @SuppressWarnings("unchecked")
    public static LicenseInformation parse(License license) {
        try {
            LinkedHashMap<String, Object> args =
                (LinkedHashMap<String, Object>) license.getExtra();

            // Mandatory attributes.
            UUID licenseId = UUID.fromString((String) args.get("licenseId"));
            String productName = (String)args.get("productName");
            int majorVersion = (Integer)args.get("majorVersion");
            int minorVersion = (Integer)args.get("minorVersion");

            // Optional attributes.
            UUID productId = args.containsKey("productId") ?
                UUID.fromString((String)args.get("productId")) : null;
            int agentQuota = args.containsKey("agentQuota") ?
                (Integer)args.get("agentQuota") : 0;

            return new LicenseInformation(
                license, licenseId, productId, productName, majorVersion,
                minorVersion, agentQuota);
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Not valid license information");
        }
    }
}
