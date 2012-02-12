package com.midokura.midolman;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import org.apache.commons.configuration.HierarchicalConfiguration;

public class TestControllerTrampoline {

    /**
     * Minimum config for constructing ControllerTrampoline in tests.
     */
    protected HierarchicalConfiguration minConfig() {
        HierarchicalConfiguration config = new HierarchicalConfiguration();

        // TODO should not depend so heavily on internals of ControllerTrampoline
        config.setProperty("midolman.midolman_root_key", "/midonet/v1/midolman");
        config.setProperty("openvswitch.midolman_ext_id_key", "midolman-vnet");

        return config;
    }
}
