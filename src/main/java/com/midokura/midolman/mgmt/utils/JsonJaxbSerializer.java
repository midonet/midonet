package com.midokura.midolman.mgmt.utils;

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
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.map.AnnotationIntrospector;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.xc.JaxbAnnotationIntrospector;

import com.midokura.midolman.util.Serializer;

public class JsonJaxbSerializer<T> implements Serializer<T> {

    private static ObjectMapper objectMapper = new ObjectMapper();
    private static JsonFactory jsonFactory = new JsonFactory(objectMapper);
    static {
        AnnotationIntrospector introspector = new JaxbAnnotationIntrospector();
        // make deserializer use JAXB annotations (only)
        objectMapper.getDeserializationConfig().withAnnotationIntrospector(
                introspector);
        // make serializer use JAXB annotations (only)
        objectMapper.getSerializationConfig().withAnnotationIntrospector(
                introspector);
    }

    public byte[] objToBytes(T obj) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        OutputStream out = new BufferedOutputStream(bos);
        JsonGenerator jsonGenerator = jsonFactory
                .createJsonGenerator(new OutputStreamWriter(out));
        jsonGenerator.writeObject(obj);
        out.close();
        return bos.toByteArray();
    }

    public T bytesToObj(byte[] data, Class<T> clazz) throws JsonParseException,
            IOException {
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        InputStream in = new BufferedInputStream(bis);
        JsonParser jsonParser = jsonFactory
                .createJsonParser(new InputStreamReader(in));
        return jsonParser.readValueAs(clazz);
    }
}
