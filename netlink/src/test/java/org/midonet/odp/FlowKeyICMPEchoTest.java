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

package org.midonet.odp;

import org.junit.Assert;
import org.junit.Test;

import org.midonet.odp.flows.FlowKeyICMPEcho;
import org.midonet.packets.ICMP;

import static org.midonet.odp.flows.FlowKeys.icmpEcho;

public class FlowKeyICMPEchoTest {

    private static final byte ICMP_ECHO_REQ = ICMP.TYPE_ECHO_REQUEST;
    private static final byte ICMP_ECHO_REPLY = ICMP.TYPE_ECHO_REPLY;
    private static final byte CODE_NONE = ICMP.CODE_NONE;

    @Test
    public void testEquals() {
        FlowKeyICMPEcho k1 = icmpEcho(ICMP_ECHO_REQ, CODE_NONE, (short)9507);
        FlowKeyICMPEcho k2 = icmpEcho(ICMP_ECHO_REQ, CODE_NONE, (short)9507);
        FlowKeyICMPEcho k3 = icmpEcho(ICMP_ECHO_REPLY, CODE_NONE, (short)9508);

        Assert.assertEquals(k1, k2);
        Assert.assertEquals(k1.hashCode(), k2.hashCode());
        Assert.assertFalse(k1.equals(k3));
        Assert.assertFalse(k1.hashCode() == k3.hashCode());
        Assert.assertFalse(k2.equals(k3));
        Assert.assertFalse(k2.hashCode() == k3.hashCode());
    }
}
