package com.midokura.midonet.smoketest.mgmt;

/*
 * Copyright 2011 Midokura Europe SARL
 */

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoRuleChain {
    private UUID id;
    private UUID routerId;
    private String name;
    private String table;
    private URI rules;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRouterId() {
        return routerId;
    }

    public void setRouterId(UUID routerId) {
        this.routerId = routerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public URI getRules() {
        return rules;
    }

    public void setRules(URI rules) {
        this.rules = rules;
    }

}
