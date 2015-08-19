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
package org.midonet.api;

import org.junit.Before;

import org.mockito.Answers;
import org.mockito.Mock;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.net.URI;

import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.doReturn;

public abstract class ResourceTest {

    public final static URI BASE_URI = URI.create("http://base_url.net");

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    protected SecurityContext context;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    protected UriInfo uriInfo;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    protected NeutronZoomPlugin plugin;

    @Before
    public void setUp() throws Exception {

        doReturn(BASE_URI).when(uriInfo).getBaseUri();
    }

    public static void assertCreate(Response resp, Object entity,
                                    URI location) {
        assertThat("Response is not null", resp, notNullValue());
        assertThat("create returned CREATED status",
                resp.getStatus(), is(Response.Status.CREATED.getStatusCode()));
        assertThat("create returned Location header",
                resp.getMetadata().containsKey("Location"));
        assertThat("create Location header is correct",
                resp.getMetadata().get("Location").get(0).toString(),
                is(location.toString()));
        assertThat("create returned resource object",
                resp.getEntity(), is(entity));
    }

    public static void assertUpdate(Response resp, Object entity) {

        assertThat("update returned OK status",
                resp.getStatus(), is(Response.Status.OK.getStatusCode()));
        assertThat("update returned Network object",
                resp.getEntity(), is(entity));
    }

}
