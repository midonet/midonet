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
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;

import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TextFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implements a topology object serializer for a specific object class.
 */
public final class ObjectSerializer {

    private static final Logger LOG =
        LoggerFactory.getLogger(ObjectSerializer.class);

    private static final TextFormat.Parser TEXT_PARSER;
    private static final Charset CHARSET;

    private final Class<? extends MessageOrBuilder> clazz;

    static {
        TEXT_PARSER = createProtoParser();
        CHARSET = Charset.forName("UTF-8");
    }

    public ObjectSerializer(Class<? extends MessageOrBuilder> clazz) {
        this.clazz = clazz;
    }

    /**
     * Converts a data object from serialized text format to a Protocol Buffers
     * message.
     * @param textData The text serialized data.
     * @return The binary serialized data.
     */
    public Message convertTextToMessage(byte[] textData) throws IOException {
        try {
            Message.Builder builder =
                (Message.Builder) clazz.getMethod("newBuilder").invoke(null);
            TEXT_PARSER.merge(new String(textData, CHARSET), builder);
            return builder.build();
        } catch (NoSuchMethodException | IllegalAccessException |
                 InvocationTargetException | TextFormat.ParseException e) {
            throw new IOException("Failed to deserialize topology object", e);
        }
    }

    /**
     * Creates a parser for Protocol Buffers text format.
     */
    private static TextFormat.Parser createProtoParser() {
        TextFormat.Parser.Builder builder = TextFormat.Parser.newBuilder();
        Class<?> builderClass = builder.getClass();

        // Set the `allowUnknownFields` using reflection: this field is private
        // and not exposed by the builder in the open-source code for Protocol
        // Buffers (according to Google this is intentional).
        try {
            Field field = builderClass.getDeclaredField("allowUnknownFields");
            field.setAccessible(true);
            field.setBoolean(builder, true);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOG.warn("Failed to set ALLOW_UNKNOWN_FIELDS for deserializing "
                     + "topology objects, therefore deserializing objects "
                     + "with deprecated or unknown fields may fail", e);
        }

        return builder.build();
    }

}
