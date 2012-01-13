/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.mgmt;

import java.net.URI;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoAdmin {
    private URI uri;
    private URI init;

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public URI getInit() {
        return init;
    }

    public void setInit(URI init) {
        this.init = init;
    }
}
