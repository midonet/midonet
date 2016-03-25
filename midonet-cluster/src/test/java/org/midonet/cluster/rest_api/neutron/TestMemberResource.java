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
package org.midonet.cluster.rest_api.neutron;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.core.MultivaluedMap;

import com.sun.jersey.core.util.MultivaluedMapImpl;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.runners.MockitoJUnitRunner;

import org.midonet.cluster.rest_api.ResourceTest;
import org.midonet.cluster.rest_api.neutron.resources.MemberResource;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class TestMemberResource extends ResourceTest {

    private MemberResource testObject;

    @Captor
    ArgumentCaptor<List<UUID>> actualIds;

    @Before
    public void setUp() throws Exception {

        super.setUp();
        testObject = new MemberResource(uriInfo, plugin);
    }

    @Test
    public void testList() throws Exception {
        doReturn(new MultivaluedMapImpl()).when(uriInfo).getQueryParameters();

        testObject.list();

        verify(plugin, times(1)).getMembers();
    }

    @Test
    public void testListWithIds() throws Exception {

        List<UUID> expectedIds = new ArrayList<>();
        expectedIds.add(UUID.randomUUID());
        expectedIds.add(UUID.randomUUID());

        List<String> inputIds  = new ArrayList<>();
        expectedIds.forEach(id -> inputIds.add(id.toString()));
        inputIds.add("BadUuid"); // bad UUID

        MultivaluedMap<String, String> queryParams = new MultivaluedMapImpl();
        queryParams.put("id", inputIds);
        queryParams.put("foo", inputIds); // random key

        doReturn(queryParams).when(uriInfo).getQueryParameters();

        testObject.list();

        verify(plugin, times(1)).getMembers(actualIds.capture());

        Assert.assertArrayEquals(expectedIds.toArray(),
                                 actualIds.getValue().toArray());
    }
}
