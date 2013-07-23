/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoTenant {

    private String id;
    private String name;

    public DtoTenant(){
    }

    public DtoTenant(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public boolean equals(Object other) {

        if (other == null) return false;
        if (other == this) return true;

        DtoTenant otherTenant = (DtoTenant) other;
        if (!otherTenant.getId().equals(this.id)) {
             return false;
        }

        if (!otherTenant.getName().equals(this.name)) {
            return false;
        }

        return true;
    }

}
