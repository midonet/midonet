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
import java.util.UUID;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.test.framework.JerseyTest;

import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;

import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.servlet.JerseyGuiceTestServletContextListener;
import org.midonet.client.MidonetApi;
import org.midonet.client.VendorMediaType;
import org.midonet.client.resource.HostVersion;
import org.midonet.client.resource.ResourceCollection;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.version.DataWriteVersion;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class TestHostVersion extends JerseyTest {

    private MidonetApi api;

    private HostZkManager hostManager;

    public TestHostVersion() {
        super(FuncTest.appDesc);
    }

    @Before
    public void setUp() throws InterruptedException,
                               KeeperException,
                               StateAccessException {
        resource().accept(VendorMediaType.APPLICATION_JSON_V5)
                .get(ClientResponse.class);
        hostManager = JerseyGuiceTestServletContextListener.getHostZkManager();
        URI baseUri = resource().getURI();
        api = new MidonetApi(baseUri.toString());
        api.enableLogging();
    }

    @Test
    public void testGet() throws StateAccessException {
        UUID myUuid = UUID.randomUUID();
        UUID anotherUuid = UUID.randomUUID();

        ResourceCollection<HostVersion> hostVersions = api.getHostVersions();
        assertThat("Hosts array should not be null", hostVersions, is(notNullValue()));
        assertThat("Hosts should be empty", hostVersions.size(), equalTo(0));

        hostManager.setHostVersion(myUuid);
        hostVersions = api.getHostVersions();

        assertThat("Hosts should be empty", hostVersions.size(), equalTo(1));
        assertThat("Host ID should not have changed",
                hostVersions.get(0).getHostId().equals(myUuid));
        assertThat("Host Version should not have changed",
                hostVersions.get(0).getVersion().equals(DataWriteVersion.CURRENT));

        hostManager.setHostVersion(anotherUuid);
        hostVersions = api.getHostVersions();

        assertThat("Hosts should be empty", hostVersions.size(), equalTo(2));
        assertThat("Host Versions should be the same",
                hostVersions.get(0).getVersion().equals(
                        hostVersions.get(1).getVersion()));
        assertThat("Host UUIDs should NOT be the same",
                !hostVersions.get(0).getHostId().equals(
                        hostVersions.get(1).getHostId()));
    }
}
