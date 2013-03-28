/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 * Copyright 2013 Midokura Europe SARL
 */
package org.midonet.midolman.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;

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

        // WARNING: don't enable default typing because it inserts types
        // in the JSON (as fully qualified class names).
        //objectMapper
        //    .enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);
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

    public static <T> String objToJsonString(T obj) throws IOException {
        StringWriter sw = new StringWriter();
        JsonGenerator gen = jsonFactory.createJsonGenerator(sw);
        gen.writeObject(obj);
        gen.close();
        // trimming needed if main-level object has leading space
        return sw.toString().trim();
    }

    public static <T> T jsonStringToObj(String data, Class<T> clazz)
        throws IOException {
        StringReader sr = new StringReader(data);
        JsonParser par = jsonFactory.createJsonParser(sr);
        T obj = par.readValueAs(clazz);
        par.close();
        return obj;
    }

    public JSONSerializer useMixin(Class<?> typeClass, Class<?> mixinClass) {

        objectMapper.getSerializationConfig()
                    .addMixInAnnotations(typeClass, mixinClass);

        objectMapper.getDeserializationConfig()
                    .addMixInAnnotations(typeClass, mixinClass);

        return this;
    }
}
