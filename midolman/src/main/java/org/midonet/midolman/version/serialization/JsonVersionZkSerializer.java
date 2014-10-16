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
package org.midonet.midolman.version.serialization;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

import com.google.inject.Inject;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.annotate.JsonAutoDetect;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.JavaType;

import org.midonet.midolman.SystemDataProvider;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.version.guice.VerCheck;
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
            mapper.setDeserializationConfig(mapper.getDeserializationConfig()
                    .withAppendedAnnotationIntrospector(intropector));
            mapper.setSerializationConfig(mapper.getSerializationConfig()
                    .withAppendedAnnotationIntrospector(intropector));
            mapper.configure(DeserializationConfig.Feature
                    .FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .setVisibilityChecker(mapper.getVisibilityChecker()
                            .withFieldVisibility(
                                    JsonAutoDetect.Visibility.ANY));

            mapperMap.putIfAbsent(version, mapper);
        }

        return mapper;
    }

    @Override
    public <T> byte[] serialize(T obj)
            throws SerializationException {

        try {
            String version = systemDataProvider.getWriteVersion();
            ObjectMapper objectMapper = getObjectMapper(version);
            VersionConfig<T> config = new VersionConfig<T>(obj, version);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            OutputStream out = new BufferedOutputStream(bos);
            JsonFactory jsonFactory = new JsonFactory(objectMapper);
            JavaType type = objectMapper.getTypeFactory()
                    .constructParametricType(VersionConfig.class,
                            obj.getClass());
            JsonGenerator jsonGenerator =
                jsonFactory.createJsonGenerator(new OutputStreamWriter(out));
            objectMapper.writerWithType(type).writeValue(jsonGenerator,
                    config);

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
                    .constructParametricType(VersionConfig.class, clazz);
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
        JsonGenerator gen = jsonFactory.createJsonGenerator(sw);
        gen.writeObject(obj);
        gen.close();
        // trimming needed if main-level object has leading space
        return sw.toString().trim();
    }

    public static <T> T jsonStringToObj(String data, Class<T> clazz)
            throws IOException {
        StringReader sr = new StringReader(data);
        JsonFactory jsonFactory = new JsonFactory(objectMapper);
        JsonParser par = jsonFactory.createJsonParser(sr);
        T obj = par.readValueAs(clazz);
        par.close();
        return obj;
    }

}
