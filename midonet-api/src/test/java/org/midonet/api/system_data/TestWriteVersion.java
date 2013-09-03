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
import static org.midonet.api.VendorMediaType.APPLICATION_WRITE_VERSION_JSON;

import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.Topology;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoWriteVersion;

public class TestWriteVersion extends JerseyTest {

    private DtoWebResource dtoResource;
    private Topology topology;

    public TestWriteVersion() {
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

        // Verify that there are no trace conditions
        URI writeVersionURI = app.getWriteVersion();
        assertNotNull(writeVersionURI);
        DtoWriteVersion writeVersion =
            dtoResource.getAndVerifyOk(writeVersionURI,
                APPLICATION_WRITE_VERSION_JSON,
                DtoWriteVersion.class);

        writeVersion.setVersion("1.5");
        dtoResource.putAndVerifyStatus(writeVersionURI,
                APPLICATION_WRITE_VERSION_JSON,
                writeVersion,
                204);

        DtoWriteVersion newWriteVersion =
            dtoResource.getAndVerifyOk(writeVersionURI,
                APPLICATION_WRITE_VERSION_JSON,
                DtoWriteVersion.class);
        assertEquals(writeVersion, newWriteVersion);
    }
}
