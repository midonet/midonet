/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.google.protobuf.MessageOrBuilder;

/**
 * The annotation specifies how a Java class should be converted to an
 * equivalent Protocol Buffers message.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE })
public @interface ZoomClass {

    /**
     * Specifies the corresponding Protocol Buffers class for this type.
     */
    Class<? extends MessageOrBuilder> clazz();

    /**
     * Specifies the converter for this class. Objects to types annotated with
     * the ZoomClass annotation, will use this converter unless overridden by
     * a different converter with the ZoomFiled annotation.
     */
    Class<? extends ZoomConvert.Converter<?,?>> converter()
        default ZoomConvert.ObjectConverter.class;

    /**
     * Specifies the factory for this class. The factory must return the Java
     * type for a given Protocol Buffers messages, such that the converter
     * can distinguish between different Java classes mapped to the same
     * Protocol Buffer message.
     */
    Class<? extends ZoomConvert.Factory<?,?>> factory()
        default ZoomConvert.DefaultFactory.class;

}
