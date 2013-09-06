/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.system_data;

import java.net.URI;

import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.midonet.api.VendorMediaType.APPLICATION_SYSTEM_STATE_JSON;

import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.Topology;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoSystemState;
import org.midonet.cluster.data.*;
import org.midonet.cluster.data.SystemState;

public class TestSystemState extends JerseyTest {

    private DtoWebResource dtoResource;
    private Topology topology;

    public TestSystemState() {
        super(FuncTest.appDesc);
    }

    @Before
    public void setUp() {
        WebResource resource = resource();
        dtoResource = new DtoWebResource(resource);
        topology = new Topology.Builder(dtoResource).build();
    }

    @Test
    public void testGetUpdate() {
        DtoApplication app = topology.getApplication();

        URI systemStateURI = app.getSystemState();
        assertNotNull(systemStateURI);
        DtoSystemState systemState =
            dtoResource.getAndVerifyOk(systemStateURI,
                APPLICATION_SYSTEM_STATE_JSON,
                DtoSystemState.class);

        systemState.setState(
                SystemState.State.UPGRADE.toString());
            dtoResource.putAndVerifyStatus(systemStateURI,
                APPLICATION_SYSTEM_STATE_JSON,
                systemState,
                204);

        DtoSystemState newSystemState =
            dtoResource.getAndVerifyOk(systemStateURI,
                APPLICATION_SYSTEM_STATE_JSON,
                DtoSystemState.class);
        assertEquals(systemState, newSystemState);

        systemState.setState(
                SystemState.State.ACTIVE.toString());
        dtoResource.putAndVerifyStatus(systemStateURI,
                APPLICATION_SYSTEM_STATE_JSON,
                systemState,
                204);

        newSystemState =
                dtoResource.getAndVerifyOk(systemStateURI,
                        APPLICATION_SYSTEM_STATE_JSON,
                        DtoSystemState.class);
        assertEquals(systemState, newSystemState);
    }
}
