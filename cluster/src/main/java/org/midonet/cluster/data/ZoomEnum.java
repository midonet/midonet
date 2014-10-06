/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.google.protobuf.ProtocolMessageEnum;

/**
 * Annotates a Java enumeration type, to provide automatic conversion to a
 * Protocol Buffers enumeration type.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE })
public @interface ZoomEnum {

    /**
     * The corresponding Protocol Buffers enumeration class.
     */
    Class<? extends ProtocolMessageEnum> clazz();

}
