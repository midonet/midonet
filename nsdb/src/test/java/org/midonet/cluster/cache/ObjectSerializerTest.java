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

package org.midonet.cluster.cache;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.UUID;

import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;

import org.junit.Assert;
import org.junit.Test;

import org.midonet.cluster.models.Topology;
import org.midonet.cluster.cache.ObjectSerializer;
import org.midonet.cluster.TopologyBuilder;

public class ObjectSerializerTest implements TopologyBuilder {

    private static final Charset CHARSET = Charset.forName("UTF-8");

    private byte[] serializeAsText(Message message) throws IOException {
        StringBuilder builder = new StringBuilder();
        TextFormat.print(message, builder);
        return builder.toString().getBytes(CHARSET);
    }

    @Test
    public void testConvertTextToBinary() throws IOException {
        // Given a serializer.
        ObjectSerializer serializer =
            new ObjectSerializer(Topology.Network.class);

        // And a topology object.
        Topology.Network network = createNetwork(UUID.randomUUID());

        // When converting the text data to binary.
        Message message = serializer.convertTextToMessage(serializeAsText(network));

        // Then the data should be the same as converting the object to binary.
        Assert.assertArrayEquals(network.toByteArray(), message.toByteArray());
    }

    @Test(expected = IOException.class)
    public void testConvertTextToBinaryMalformed() throws IOException {
        // Given a serializer.
        ObjectSerializer serializer =
            new ObjectSerializer(Topology.Network.class);

        // And some random data.
        byte[] data = new byte[10];

        // Then converting the data to Protocol Buffer message should fail.
        serializer.convertTextToMessage(data);
    }

}
