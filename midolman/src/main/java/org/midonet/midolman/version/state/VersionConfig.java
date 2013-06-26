/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.midolman.version.state;

/**
 * The outer layer of the ZK data that holds its metadata.
 */
public class VersionConfig <T> {

    private T data;
    private String version;

    public VersionConfig() {
        // Default constructor needed for Jackson
    }

    public VersionConfig(T data, String version) {
        this.data = data;
        this.version = version;
    }

    public T getData() {
        return this.data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getVersion() {
        return this.version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
