/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotates a Java enumeration field to provide automatic conversion to a
 * Protocol Buffers enumeration type.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD })
public @interface ZoomEnumValue {

    /**
     * The name of the Protocol Buffers enumeration value.
     */
    String value();
}
