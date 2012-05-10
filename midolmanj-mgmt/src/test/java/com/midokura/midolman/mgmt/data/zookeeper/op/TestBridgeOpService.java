/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import junit.framework.Assert;

import org.apache.zookeeper.Op;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import com.midokura.midolman.mgmt.data.dao.zookeeper.BridgeZkDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.dto.config.BridgeMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;

@RunWith(MockitoJUnitRunner.class)
public class TestBridgeOpService {

	private BridgeOpService testObject;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private BridgeOpBuilder opBuilder;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private BridgeZkDao zkDao;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private PortOpService portOpService;

	@Before
	public void setUp() {
		testObject = new BridgeOpService(opBuilder, portOpService, zkDao);
	}

	@Test
	public void testBuildCreateBridgeSuccess() throws Exception {

		// Setup
		UUID id = UUID.randomUUID();
		BridgeConfig config = new BridgeConfig();
		BridgeMgmtConfig mgmtConfig = new BridgeMgmtConfig("foo", "bar");
		BridgeNameMgmtConfig nameConfig = new BridgeNameMgmtConfig();
		InOrder inOrder = inOrder(opBuilder);

		// Execute
		List<Op> ops = testObject.buildCreate(id, config, mgmtConfig,
				nameConfig);

		// Verify the order of execution
		Assert.assertTrue(ops.size() > 0);
		inOrder.verify(opBuilder).getBridgeCreateOps(id, config);
		inOrder.verify(opBuilder).getBridgeCreateOp(id, mgmtConfig);
		inOrder.verify(opBuilder).getTenantBridgeNameCreateOp(
				mgmtConfig.tenantId, mgmtConfig.name, nameConfig);
	}

	@Test
	public void testBuildDeleteWithCascadeSuccess() throws Exception {

		// Setup
		UUID id = UUID.randomUUID();
		InOrder inOrder = inOrder(opBuilder, portOpService);

		// Execute
		List<Op> ops = testObject.buildDelete(id, true);

		// Verify the order of execution
		Assert.assertTrue(ops.size() > 0);
		inOrder.verify(opBuilder).getBridgeDeleteOps(id);
		inOrder.verify(portOpService).buildBridgePortsDelete(id);
		inOrder.verify(opBuilder).getBridgeDeleteOp(id);
	}

	@Test
	public void testBuildDeleteWithNoCascadeSuccess() throws Exception {

		// Setup
		UUID id = UUID.randomUUID();

		// Execute
		List<Op> ops = testObject.buildDelete(id, false);

		// Verify that cascade did not happen
		Assert.assertTrue(ops.size() > 0);
		verify(opBuilder, never()).getBridgeDeleteOps(id);
	}

	@Ignore
    @Test
	public void testBuildUpdateSuccess() throws Exception {

		// Setup
		UUID id = UUID.randomUUID();
		final String name = "foo";

		// Make sure that the name that's updated is the new name.
		doAnswer(new Answer<Object>() {
			@Override
			public Object answer(InvocationOnMock invocation) throws Throwable {
				BridgeMgmtConfig config = (BridgeMgmtConfig) invocation
						.getArguments()[1];
				Assert.assertEquals(config.name, name);
				return null;
			}
		}).when(opBuilder).getBridgeSetDataOp(any(UUID.class),
				any(BridgeMgmtConfig.class));

		// Execute
		List<Op> ops = testObject.buildUpdate(new Bridge());

		// Verify
		Assert.assertTrue(ops.size() > 0);
	}
}
