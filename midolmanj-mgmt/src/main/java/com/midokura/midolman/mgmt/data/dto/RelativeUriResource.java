package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import javax.xml.bind.annotation.XmlTransient;

public abstract class RelativeUriResource {

    private URI parentUri = null;

    /**
     * Default constructor
     */
    public RelativeUriResource() {
    }

    /**
     * @return the parentUri
     */
    @XmlTransient
    public URI getParentUri() {
        return parentUri;
    }

    /**
     * @param parentUri
     *            the parentUri to set
     */
    public void setParentUri(URI parentUri) {
        this.parentUri = parentUri;
    }

    /**
     * @return URI of the resource.
     */
    public abstract URI getUri();
}
