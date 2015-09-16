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

package org.midonet.cluster;

import java.util.List;

import org.junit.Ignore;
import org.junit.Test;

import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.midolman.state.StateAccessException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;


public class LocalDataClientImplTest extends LocalDataClientImplTestBase {

    @Test
    @Ignore // SEE MNA-858
    public void checkHealthMonitorNodeTest() throws StateAccessException {
        /*
        final boolean[] currentLeader = {false, false, false, false};

        // The var accessed inside of functors has to be final, otherwise
        // I would have just used a single int.
        ExecuteOnBecomingLeader cb0 = new ExecuteOnBecomingLeader() {
            @Override
            public void call() {
                currentLeader[0] = true;
                currentLeader[1] = false;
                currentLeader[2] = false;
                currentLeader[3] = false;
            }
        };
        ExecuteOnBecomingLeader cb1 = new ExecuteOnBecomingLeader() {
            @Override
            public void call() {
                currentLeader[0] = false;
                currentLeader[1] = true;
                currentLeader[2] = false;
                currentLeader[3] = false;
            }
        };
        ExecuteOnBecomingLeader cb2 = new ExecuteOnBecomingLeader() {
            @Override
            public void call() {
                 * Don't do anything. This makes the UT weaker, but we have
                 * no way to remove watches so even if we remove the
                 * node corresponding to this callback, the watch callback
                 * will still be triggered. This isn't a problem in production
                 * because the node is removed when the mm agent goes away.
                 *
                 * Functionality to remove watches is in ZooKeeper 3.5.0+
            }
        };
        ExecuteOnBecomingLeader cb3 = new ExecuteOnBecomingLeader() {
            @Override
            public void call() {
                currentLeader[0] = false;
                currentLeader[1] = false;
                currentLeader[2] = false;
                currentLeader[3] = true;
            }
        };

        Integer precLeader = client.getPrecedingHealthMonitorLeader(14);
        assertThat(precLeader, equalTo(null));

        Integer hostNum0 = client.registerAsHealthMonitorNode(cb0);
        // Make sure the preceding leader for an arbitrary number is hostNum0
        precLeader = client.getPrecedingHealthMonitorLeader(1);
        assertThat(precLeader, equalTo(hostNum0));
        assertThat(hostNum0, equalTo(0));
        assertIsLeader(currentLeader, hostNum0);

        Integer hostNum1 = client.registerAsHealthMonitorNode(cb1);
        // Make sure the preceding leader for an arbitrary number is hostNum1
        precLeader = client.getPrecedingHealthMonitorLeader(6);
        assertThat(precLeader, equalTo(hostNum1));
        assertThat(hostNum1, equalTo(1));
        assertIsLeader(currentLeader, hostNum0);

        // host 0 goes down...
        client.removeHealthMonitorLeaderNode(hostNum0);
        assertIsLeader(currentLeader, hostNum1);

        Integer hostNum2 = client.registerAsHealthMonitorNode(cb2);
        precLeader = client.getPrecedingHealthMonitorLeader(hostNum2);
        assertThat(precLeader, equalTo(hostNum1));
        assertThat(hostNum2, equalTo(2));
        assertIsLeader(currentLeader, hostNum1);

        Integer hostNum3 = client.registerAsHealthMonitorNode(cb3);
        precLeader = client.getPrecedingHealthMonitorLeader(hostNum3);
        assertThat(precLeader, equalTo(hostNum2));

        // host 2 goes down
        client.removeHealthMonitorLeaderNode(hostNum2);
        assertIsLeader(currentLeader, hostNum1);
        precLeader = client.getPrecedingHealthMonitorLeader(hostNum3);
        assertThat(precLeader, equalTo(hostNum1));

        //host 1 goes down
        client.removeHealthMonitorLeaderNode(hostNum1);
        assertIsLeader(currentLeader, hostNum3);
        precLeader = client.getPrecedingHealthMonitorLeader(hostNum3);
        assertThat(precLeader, equalTo(null));
        */
    }

    private void assertSubnetCidrs(List<Subnet> actual,
                                   List<String> expectedCidrs) {
        assertThat(actual, notNullValue());
        assertThat(expectedCidrs, notNullValue());

        assertThat(actual.size(), equalTo(expectedCidrs.size()));

        for (Subnet actualSubnet : actual) {
            assertThat(expectedCidrs,
                    hasItem(actualSubnet.getSubnetAddr().toZkString()));
        }
    }
}
