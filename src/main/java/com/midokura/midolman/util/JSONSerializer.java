/*
 * @(#)Serialization        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
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

import org.apache.zookeeper.KeeperException;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.annotate.JsonAutoDetect.Visibility;
import org.codehaus.jackson.map.ObjectMapper;

/**
 * Serialization utility class.
 * 
 * @version        1.6 10 Sept 2011
 * @author         Ryu Ishimoto
 */
public class JSONSerializer<T> {

    private static ObjectMapper objectMapper = new ObjectMapper();
    private static JsonFactory jsonFactory = new JsonFactory(objectMapper);
    static {
       objectMapper.setVisibilityChecker(
           objectMapper.getVisibilityChecker().withFieldVisibility(Visibility.ANY));
       objectMapper.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);
    }
    
    
    public byte[] objToBytes(Object obj) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        OutputStream out = new BufferedOutputStream(bos);
        JsonGenerator jsonGenerator =
            jsonFactory.createJsonGenerator(new OutputStreamWriter(out));
        jsonGenerator.writeObject(obj);
        out.close();        
        return bos.toByteArray();
    }

    public Object bytesToObj(byte[] data, Class<T> clazz)
        throws IOException, ClassNotFoundException, KeeperException,
            InterruptedException {
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        InputStream in = new BufferedInputStream(bis);
        JsonParser jsonParser =
            jsonFactory.createJsonParser(new InputStreamReader(in));
        return jsonParser.readValueAs(clazz);        
    }
}
