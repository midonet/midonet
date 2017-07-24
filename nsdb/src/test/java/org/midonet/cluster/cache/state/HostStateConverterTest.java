/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster.cache.state;

import org.junit.Assert;
import org.junit.Test;

import org.midonet.cluster.models.Commons;
import org.midonet.cluster.models.State;

public class HostStateConverterTest {

    @Test
    public void testValidState() throws Exception {
        // Given a host state converter.
        StateConverter converter = new HostStateConverter();

        // And a host state.
        State.HostState state = State.HostState.newBuilder()
            .setHostId(Commons.UUID.newBuilder().setMsb(1L).setLsb(2L).build())
            .addInterfaces(State.HostState.Interface.newBuilder()
                               .setName("eth1")
                               .setMac("01:02:03:04:05:06")
                               .setMtu(1500))
            .addInterfaces(State.HostState.Interface.newBuilder()
                               .setName("eth2")
                               .setMac("01:02:03:04:05:06")
                               .setMtu(1500))
            .build();

        // When converting the state.
        byte[] converted = converter.singleValue(state.toString().getBytes());

        // Then the state should be parsable.
        State.HostState convertedState = State.HostState.parseFrom(converted);

        // And the converted state should contain the same data.
        Assert.assertEquals(state, convertedState);
    }

    @Test
    public void testInvalidState() {
        // Given a host state converter.
        StateConverter converter = new HostStateConverter();

        // And some random data.
        byte[] input = "Cannot be converted to host state".getBytes();

        // When converting the data.
        byte[] converted = converter.singleValue(input);

        // Then the state should be empty.
        Assert.assertEquals(0, converted.length);
    }

}
