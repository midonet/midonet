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
package org.midonet.midolman.cluster.serialization;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;

import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.midolman.SystemDataProvider;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.version.state.VersionConfig;
import org.midonet.util.version.VersionCheckAnnotationIntrospector;

/**
 * Serialization utility class that is version-aware.
 */
public class JsonVersionZkSerializer implements Serializer {

    private final SystemDataProvider systemDataProvider;
    private final Comparator<String> versionComparator;
    private static ObjectMapper objectMapper;

    private static ConcurrentHashMap<String, ObjectMapper> mapperMap =
            new ConcurrentHashMap<>();

    static {
        objectMapper = new ObjectMapper();
        objectMapper.setVisibilityChecker(
                objectMapper.getVisibilityChecker()
                        .withFieldVisibility(JsonAutoDetect.Visibility.ANY));
    }

    @Inject
    public JsonVersionZkSerializer(SystemDataProvider systemDataProvider,
                                   @VerCheck Comparator<String> comp) {
        this.systemDataProvider = systemDataProvider;
        this.versionComparator = comp;
    }

    public ObjectMapper getObjectMapper(String version) {
        ObjectMapper mapper = mapperMap.get(version);
        if (mapper == null) {
            mapper = new ObjectMapper();
            VersionCheckAnnotationIntrospector intropector =
                    new VersionCheckAnnotationIntrospector(version,
                            this.versionComparator);
            mapper.setConfig(mapper.getDeserializationConfig()
                    .withAppendedAnnotationIntrospector(intropector));
            mapper.setConfig(mapper.getSerializationConfig()
                                 .withAppendedAnnotationIntrospector(
                                     intropector));
            mapper.configure(DeserializationFeature
                    .FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .setVisibilityChecker(mapper.getVisibilityChecker()
                                              .withFieldVisibility(
                                                  JsonAutoDetect.Visibility.ANY));

            mapperMap.putIfAbsent(version, mapper);
        }

        return mapper;
    }

    @Override
    public <T> byte[] serialize(T obj) throws SerializationException {

        try {
            String version = systemDataProvider.getWriteVersion();
            ObjectMapper objectMapper = getObjectMapper(version);
            VersionConfig<T> config = new VersionConfig<>(obj, version);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            OutputStream out = new BufferedOutputStream(bos);
            JsonFactory jsonFactory = new JsonFactory(objectMapper);
            JavaType type = objectMapper.getTypeFactory()
                    .constructParametrizedType(VersionConfig.class,
                                               VersionConfig.class,
                                               obj.getClass());
            JsonGenerator jsonGenerator =
                jsonFactory.createGenerator(new OutputStreamWriter(out));
            objectMapper.writerFor(type).writeValue(jsonGenerator, config);

            jsonGenerator.close();
            out.close();

            return bos.toByteArray();
        } catch (IOException e) {
            throw new SerializationException(
                    "Could not serialize the class.", e, obj.getClass());
        } catch (StateAccessException e) {
            throw new SerializationException(
                    "Could not get the write version from ZK", e);
        }
    }

    @Override
    public <T> T deserialize(byte[] data, Class<T> clazz)
            throws SerializationException {
        try {
            String version = systemDataProvider.getWriteVersion();
            ObjectMapper objectMapper = getObjectMapper(version);

            JavaType type = objectMapper.getTypeFactory()
                    .constructParametrizedType(VersionConfig.class,
                                               VersionConfig.class, clazz);
            VersionConfig<T> config = objectMapper.readValue(data, type);
            return config.getData();
        } catch (IOException e) {
            throw new SerializationException(
                    "Could not deserialize the class.", e, clazz);
        } catch (StateAccessException e) {
            throw new SerializationException(
                    "Could not get the write version from ZK", e);
        }
    }

    public static <T> String objToJsonString(T obj)
            throws IOException {
        StringWriter sw = new StringWriter();
        JsonFactory jsonFactory = new JsonFactory(objectMapper);
        JsonGenerator gen = jsonFactory.createGenerator(sw);
        gen.writeObject(obj);
        gen.close();
        // trimming needed if main-level object has leading space
        return sw.toString().trim();
    }

    public static <T> T jsonStringToObj(String data, Class<T> clazz)
            throws IOException {
        StringReader sr = new StringReader(data);
        JsonFactory jsonFactory = new JsonFactory(objectMapper);
        JsonParser par = jsonFactory.createParser(sr);
        T obj = par.readValueAs(clazz);
        par.close();
        return obj;
    }

}
