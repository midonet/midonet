/*
 * @(#)Chain      1.6 11/09/10
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.config.ChainMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.ChainNameMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.ChainTable;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;

/**
 * Class representing chain.
 *
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class Chain extends UriResource {

    private UUID id = null;
    private UUID routerId = null;
    private String name = null;
    private ChainTable table = null;

    /**
     * Default constructor
     */
    public Chain() {
        super();
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the chain
     * @param config
     *            ChainConfig object
     * @param mgmtConfig
     *            ChainMgmtConfig object
     */
    public Chain(UUID id, ChainConfig config, ChainMgmtConfig mgmtConfig) {
        this(id, config.routerId, mgmtConfig.table, config.name);
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the chain
     * @param routerId
     *            Router ID
     * @param table
     *            Chain table
     * @param name
     *            Chain name
     */
    public Chain(UUID id, UUID routerId, ChainTable table, String name) {
        this.id = id;
        this.routerId = routerId;
        this.table = table;
        this.name = name;
    }

    /**
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * @param id
     *            the id to set
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * @return the routerId
     */
    public UUID getRouterId() {
        return routerId;
    }

    /**
     * @param routerId
     *            the routerId to set
     */
    public void setRouterId(UUID routerId) {
        this.routerId = routerId;
    }

    /**
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * @param name
     *            the name to set
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return the table
     */
    public ChainTable getTable() {
        return table;
    }

    /**
     * @param table
     *            the table to set
     */
    public void setTable(ChainTable table) {
        this.table = table;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return ResourceUriBuilder.getChain(getBaseUri(), id);
    }

    /**
     * @return the rules URI
     */
    public URI getRules() {
        return ResourceUriBuilder.getChainRules(getBaseUri(), id);
    }

    public ChainConfig toConfig() {
        return new ChainConfig(this.getName(), this.getRouterId());
    }

    public ChainMgmtConfig toMgmtConfig() {
        return new ChainMgmtConfig(this.getTable());
    }

    public ChainNameMgmtConfig toNameMgmtConfig() {
        return new ChainNameMgmtConfig(this.getId());
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "id=" + id + " routerId=" + routerId + ", table=" + table
                + ", name=" + name;
    }

}
