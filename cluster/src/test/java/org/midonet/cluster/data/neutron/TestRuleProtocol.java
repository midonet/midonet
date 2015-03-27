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
package org.midonet.cluster.data.neutron;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import junitparams.JUnitParamsRunner;
import static junitparams.JUnitParamsRunner.$;
import junitparams.Parameters;

@RunWith(JUnitParamsRunner.class)
public class TestRuleProtocol {

    public static class ProtocolProvider {

        public static Object[] paramsForProtocols() {
            return $(
                $("6", "tcp", RuleProtocol.TCP),
                $("17", "udp", RuleProtocol.UDP),
                $("1", "icmp", RuleProtocol.ICMP),
                $("58", "icmpv6", RuleProtocol.ICMPv6)
            );
        }
    }

    @Test
    @Parameters(source = ProtocolProvider.class, method="paramsForProtocols")
    public void testForValue(String num, String val, RuleProtocol expected) {

        RuleProtocol p = RuleProtocol.forValue(num);
        Assert.assertEquals(expected, p);

        p = RuleProtocol.forValue(val);
        Assert.assertEquals(expected, p);
    }
}
