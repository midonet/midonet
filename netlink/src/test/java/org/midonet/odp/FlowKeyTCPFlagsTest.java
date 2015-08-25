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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.flows.FlowKeyTCPFlags;
import org.midonet.packets.TCP;

import static org.midonet.odp.flows.FlowKeys.tcpFlags;

public class FlowKeyTCPFlagsTest {

    @Test
    public void testGetSetFlag() {
        List<TCP.Flag> flagsList = new ArrayList<>();
        flagsList.add(TCP.Flag.Ack);

        FlowKeyTCPFlags flags1 = tcpFlags(flagsList);

        Assert.assertTrue(flags1.getFlag(TCP.Flag.Ack));
        Assert.assertFalse(flags1.getFlag(TCP.Flag.Syn));
        Assert.assertFalse(flags1.getFlag(TCP.Flag.Fin));

        flags1.clearFlag(TCP.Flag.Ack);
        flags1.setFlag(TCP.Flag.Syn);
        flags1.setFlag(TCP.Flag.Fin);

        Assert.assertFalse(flags1.getFlag(TCP.Flag.Ack));
        Assert.assertTrue(flags1.getFlag(TCP.Flag.Syn));
        Assert.assertTrue(flags1.getFlag(TCP.Flag.Fin));
    }

    @Test
    public void testSerialization() {
        List<TCP.Flag> flagsList = new ArrayList<>();
        flagsList.add(TCP.Flag.Ack);
        flagsList.add(TCP.Flag.Rst);

        FlowKeyTCPFlags flags1 = tcpFlags(flagsList);
        FlowKeyTCPFlags flags2 = tcpFlags((short) 0);

        ByteBuffer buf = BytesUtil.instance.allocate(256);
        flags1.serializeInto(buf);
        buf.flip();
        flags2.deserializeFrom(buf);

        Assert.assertEquals(flags1, flags2);
        Assert.assertTrue(flags2.getFlag(TCP.Flag.Ack));
        Assert.assertTrue(flags2.getFlag(TCP.Flag.Rst));
    }
}
