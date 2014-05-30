/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.client.dto;

import java.util.Objects;

public class DtoVtepPort {
    private String name;
    private String description;

    public DtoVtepPort() {}

    public DtoVtepPort(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoVtepPort that = (DtoVtepPort)o;
        return Objects.equals(name, that.name) &&
                Objects.equals(description, that.description);
    }

    @Override
    public String toString() {
        return "DtoVtepPort{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                '}';
    }
}
