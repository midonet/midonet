/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.api.system_data;

import java.net.URI;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.test.framework.JerseyTest;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import org.midonet.api.rest_api.FuncTest;
import org.midonet.client.MidonetApi;
import org.midonet.cluster.rest_api.VendorMediaType;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.midonet.midolman.version.DataWriteVersion.CURRENT;

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
    @Ignore("TODO FIXME - pending implementation in v2")
    public void testGetUpdate() {
        org.midonet.client.resource.WriteVersion writeVersion =
                api.getWriteVersion();
        assertThat("The version should be the current version",
                writeVersion.getVersion().equals(CURRENT));

        writeVersion.version("1.100");

        writeVersion.update();
        org.midonet.client.resource.WriteVersion writeVersion1
                = api.getWriteVersion();

        assertThat("The versions should be the same.",
                writeVersion.getVersion().equalsIgnoreCase(writeVersion1.getVersion()));
    }
}
