/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.tracing;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.midonet.packets.Unsigned;
import org.midonet.util.Range;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.Topology;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoPortGroup;
import org.midonet.client.dto.DtoTraceCondition;
import org.midonet.packets.ARP;
import static org.midonet.api.VendorMediaType.APPLICATION_CONDITION_COLLECTION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_CONDITION_JSON;

@RunWith(Enclosed.class)
public class TestTraceCondition {

    @RunWith(Parameterized.class)
    public static class TestTraceConditionCrudSuccess extends JerseyTest {
        private DtoTraceCondition traceCondition;
        private DtoWebResource dtoResource;
        private Topology topology;

        public TestTraceConditionCrudSuccess(DtoTraceCondition traceCondition) {
            super(FuncTest.appDesc);
            this.traceCondition = traceCondition;
        }

        @Before
        public void setUp() {
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);
            topology = new Topology.Builder(dtoResource).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Parameterized.Parameters
        public static Collection<Object[]> data() {
            UUID[] inPorts = new UUID[] { UUID.randomUUID(), UUID.randomUUID(),
                                        UUID.randomUUID() };
            UUID[] outPorts = new UUID[] { UUID.randomUUID() };

            DtoTraceCondition traceCondition = new DtoTraceCondition();
            traceCondition.setCondInvert(true);
            traceCondition.setMatchForwardFlow(true);
            traceCondition.setMatchReturnFlow(false);
            traceCondition.setInPorts(inPorts);
            traceCondition.setInvInPorts(true);
            traceCondition.setOutPorts(outPorts);
            traceCondition.setInvOutPorts(false);
            traceCondition.setPortGroup(UUID.randomUUID());
            traceCondition.setInvPortGroup(true);
            traceCondition.setDlType(0x601);
            traceCondition.setInvDlType(false);
            traceCondition.setDlSrc("00:11:22:33:44:55");
            traceCondition.setInvDlSrc(true);
            traceCondition.setDlDst("06:05:04:03:02:01");
            traceCondition.setInvDlDst(false);
            traceCondition.setNwTos(20);
            traceCondition.setInvNwTos(true);
            traceCondition.setNwProto(6);
            traceCondition.setInvNwProto(false);
            traceCondition.setNwSrcAddress("10.0.0.2");
            traceCondition.setNwSrcLength(24);
            traceCondition.setInvNwSrc(true);
            traceCondition.setNwDstAddress("192.168.100.10");
            traceCondition.setNwDstLength(32);
            traceCondition.setInvNwDst(false);
            traceCondition.setTpSrc(
                new DtoTraceCondition.DtoRange<Integer>(1024, 3000));
            traceCondition.setInvTpDst(true);
            traceCondition.setTpDst(
                new DtoTraceCondition.DtoRange<Integer>(1024, 3000));
                traceCondition.setInvTpDst(false);

            DtoTraceCondition partialTraceCondition = new DtoTraceCondition();
            partialTraceCondition.setCondInvert(false);
            partialTraceCondition.setOutPorts(outPorts);
            partialTraceCondition.setInvPortGroup(false);
            partialTraceCondition.setDlType(0x1201);
            partialTraceCondition.setInvDlType(false);
            partialTraceCondition.setDlDst("00:11:22:33:44:55");
            partialTraceCondition.setNwTos(35);
            partialTraceCondition.setNwProto(5);
            partialTraceCondition.setNwDstAddress("1.2.3.4");
            partialTraceCondition.setNwDstLength(32);
            partialTraceCondition.setTpDst(
                new DtoTraceCondition.DtoRange<Integer>(1024, 3000));

            DtoTraceCondition emptyTraceCondition = new DtoTraceCondition();

            return Arrays.asList(new Object[][] { { traceCondition },
                { partialTraceCondition }, { emptyTraceCondition } } );
        }

        @Test
        public void testCreateGetListDelete() {
            DtoApplication app = topology.getApplication();

            // Verify that there are no trace conditions
            URI traceConditionsUri = app.getTraceConditions();
            assertNotNull(traceConditionsUri);
            DtoTraceCondition[] traceConditions =
                dtoResource.getAndVerifyOk(traceConditionsUri,
                    APPLICATION_CONDITION_COLLECTION_JSON,
                    DtoTraceCondition[].class);
            assertEquals(0, traceConditions.length);

            // Add a trace condition
            DtoTraceCondition outTraceCondition =
                dtoResource.postAndVerifyCreated(traceConditionsUri,
                    APPLICATION_CONDITION_JSON, traceCondition,
                    DtoTraceCondition.class);
            assertNotNull(outTraceCondition);
            URI traceCondition1Uri = outTraceCondition.getUri();
            UUID traceCondition1Id = outTraceCondition.getId();
            traceCondition.setUri(traceCondition1Uri);
            traceCondition.setId(traceCondition1Id);
            assertEquals(traceCondition, outTraceCondition);

            // List the trace condition
            traceConditions = dtoResource.getAndVerifyOk(traceConditionsUri,
                APPLICATION_CONDITION_COLLECTION_JSON,
                DtoTraceCondition[].class);
            assertEquals(1, traceConditions.length);
            assertEquals(outTraceCondition, traceConditions[0]);

            // Add a second trace condition
            outTraceCondition =
                dtoResource.postAndVerifyCreated(traceConditionsUri,
                    APPLICATION_CONDITION_JSON, traceCondition,
                    DtoTraceCondition.class);
            assertNotNull(outTraceCondition);
            URI traceCondition2Uri = outTraceCondition.getUri();
            UUID traceCondition2Id = outTraceCondition.getId();
            traceCondition.setUri(traceCondition2Uri);
            traceCondition.setId(traceCondition2Id);
            assertEquals(traceCondition, outTraceCondition);

            // List both trace conditions
            traceConditions = dtoResource.getAndVerifyOk(traceConditionsUri,
                APPLICATION_CONDITION_COLLECTION_JSON,
                DtoTraceCondition[].class);
            assertEquals(2, traceConditions.length);

            // Delete one of the trace conditions
            dtoResource.deleteAndVerifyNoContent(traceCondition1Uri,
                                                 APPLICATION_CONDITION_JSON);

            // Verify that the condition is gone
            dtoResource.getAndVerifyNotFound(traceCondition1Uri,
                                             APPLICATION_CONDITION_JSON);

            // List and make sure there is only one trace condition
            traceConditions = dtoResource.getAndVerifyOk(traceConditionsUri,
                APPLICATION_CONDITION_COLLECTION_JSON,
                DtoTraceCondition[].class);
            assertEquals(1, traceConditions.length);

            // Delete the second trace condition
            dtoResource.deleteAndVerifyNoContent(traceCondition2Uri,
                                                 APPLICATION_CONDITION_JSON);

            // Verify that the trace condition is gone
            dtoResource.getAndVerifyNotFound(traceCondition2Uri,
                                             APPLICATION_CONDITION_JSON);

            // List should return nothing now.
            traceConditions = dtoResource.getAndVerifyOk(traceConditionsUri,
                APPLICATION_CONDITION_COLLECTION_JSON,
                DtoTraceCondition[].class);
            assertEquals(0, traceConditions.length);
        }
    }
}
