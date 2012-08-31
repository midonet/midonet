/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.annotate.JsonAutoDetect.Visibility;
import org.codehaus.jackson.map.ObjectMapper;

/**
 * Serialization utility class.
 *
 * @author Taku Fukushima
 */
public class JSONSerializer implements Serializer {

    protected static ObjectMapper objectMapper = new ObjectMapper();

    protected static JsonFactory jsonFactory = new JsonFactory(objectMapper);

    static {
        objectMapper.setVisibilityChecker(
            objectMapper.getVisibilityChecker()
                        .withFieldVisibility(Visibility.ANY));

        objectMapper
            .enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);
    }

    @Override
    public <T> byte[] objToBytes(T obj) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        OutputStream out = new BufferedOutputStream(bos);
        JsonGenerator jsonGenerator =
            jsonFactory.createJsonGenerator(new OutputStreamWriter(out));
        jsonGenerator.writeObject(obj);
        out.close();
        return bos.toByteArray();
    }

    @Override
    public <T> T bytesToObj(byte[] data, Class<T> clazz)
        throws IOException {
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        InputStream in = new BufferedInputStream(bis);
        JsonParser jsonParser =
            jsonFactory.createJsonParser(new InputStreamReader(in));

        //noinspection unchecked
        return jsonParser.readValueAs(clazz);
    }

    public JSONSerializer useMixin(Class<?> typeClass, Class<?> mixinClass) {

        objectMapper.getSerializationConfig()
                    .addMixInAnnotations(typeClass, mixinClass);

        objectMapper.getDeserializationConfig()
                    .addMixInAnnotations(typeClass, mixinClass);

        return this;
    }
}
