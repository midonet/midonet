/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.system_data;

import java.net.URI;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.Before;
import org.junit.Test;

import org.midonet.api.rest_api.FuncTest;
import org.midonet.client.MidonetApi;
import org.midonet.client.resource.*;
import org.midonet.cluster.data.*;
import org.midonet.cluster.data.SystemState;
import org.midonet.client.VendorMediaType;
import org.midonet.midolman.version.DataWriteVersion;

import static org.hamcrest.MatcherAssert.assertThat;

public class TestWriteVersion extends JerseyTest {

    private MidonetApi api;

    public TestWriteVersion() {
        super(FuncTest.appDesc);
    }

    @Before
    public void setUp() {

        resource().accept(VendorMediaType.APPLICATION_JSON_V5)
                .get(ClientResponse.class);
        URI baseUri = resource().getURI();
        api = new MidonetApi(baseUri.toString());
        api.enableLogging();
    }

    @Test
    public void testGetUpdate() {
        org.midonet.client.resource.WriteVersion writeVersion =
                api.getWriteVersion();
        assertThat("The version should be the current version",
                writeVersion.getVersion().equals(
                        DataWriteVersion.CURRENT));

        writeVersion.version("1.100");

        writeVersion.update();
        org.midonet.client.resource.WriteVersion writeVersion1
                = api.getWriteVersion();

        assertThat("The versions should be the same.",
                writeVersion.getVersion().equalsIgnoreCase(writeVersion1.getVersion()));
    }
}
