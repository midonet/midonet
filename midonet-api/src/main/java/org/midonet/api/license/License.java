/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.license;

import java.net.URI;
import java.text.SimpleDateFormat;
import javax.validation.constraints.NotNull;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.license.LicenseInformation;

/**
 * Contains private license information.
 */
@XmlRootElement
public class License extends UriResource {
    private static final SimpleDateFormat dateFormat =
        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    @NotNull
    private String id;
    @NotNull
    private String issuerX500;
    @NotNull
    private String holderX500;
    @NotNull
    private String issueDate;
    @NotNull
    private String startDate;
    @NotNull
    private String endDate;
    @NotNull
    private String product;
    @NotNull
    private String description;
    @NotNull
    private LicenseExtra extra;
    private boolean valid;

    public License() { }

    /**
     * Creates a license information instance from a MidoNet license bean.
     * @param license The TrueLicense license bean.
     * @param valid True if te license is valid, false otherwise.
     */
    public License(net.java.truelicense.core.License license,
                   boolean valid ) {
        LicenseInformation info;
        try {
            info = LicenseInformation.parse(license);
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Not a MidoNet license.");
        }

        id = info.getLicenseId().toString();
        issuerX500 = license.getIssuer().toString();
        holderX500 = license.getHolder().toString();
        issueDate = dateFormat.format(license.getIssued());
        startDate = dateFormat.format(license.getNotBefore());
        endDate = dateFormat.format(license.getNotAfter());
        product = license.getSubject();
        description = license.getInfo();
        extra = new LicenseExtra(info);
        this.valid = valid;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getIssuerX500() {
        return issuerX500;
    }

    public String getHolderX500() {
        return holderX500;
    }

    public String getIssueDate() {
        return issueDate;
    }

    public String getStartDate() {
        return startDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public String getProduct() {
        return product;
    }

    public String getDescription() {
        return description;
    }

    public LicenseExtra getExtra() {
        return extra;
    }

    public boolean isValid() {
        return valid;
    }

    /**
     * @return URI of the resource.
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getLicense(getBaseUri(), id);
        } else {
            return null;
        }
    }

    @Override
    public boolean equals(Object object) {
        if (null == object) return false;
        if (!(object instanceof License)) return false;
        License license = (License)object;
        return this.id.equals(license.id);
    }

    @Override
    public int hashCode() {
        return this.id.hashCode();
    }
}
