package com.midokura.midolman.mgmt.data.dto.client;

/*
 * Copyright 2011 Midokura Europe SARL
 */

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoRuleChain {

    private UUID id;
    private UUID ownerId;
    private String name;
    private URI rules;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public URI getRules() {
        return rules;
    }

    public void setRules(URI rules) {
        this.rules = rules;
    }

}
