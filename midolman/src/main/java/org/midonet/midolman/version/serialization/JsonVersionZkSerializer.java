/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 * Copyright 2013 Midokura Europe SARL
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.midolman.version.serialization;

import com.google.inject.Inject;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.annotate.JsonAutoDetect;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.JavaType;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.SystemDataProvider;
import org.midonet.midolman.version.guice.VerCheck;
import org.midonet.midolman.version.state.VersionConfig;
import org.midonet.util.version.VersionCheckAnnotationIntrospector;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.BufferedOutputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.StringReader;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serialization utility class that is version-aware.
 */
public class JsonVersionZkSerializer implements Serializer {

    private final SystemDataProvider systemDataProvider;
    private final Comparator versionComparator;
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
                                   @VerCheck Comparator versionComparator) {
        this.systemDataProvider = systemDataProvider;
        this.versionComparator = versionComparator;
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
