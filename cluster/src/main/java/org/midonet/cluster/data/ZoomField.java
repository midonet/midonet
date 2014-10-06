/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An annotation for a ZOOM object field. The annotation creates a mapping
 * between a Java object field and the corresponding Protocol Buffers message
 * field. Currently, the mapping is done by name, however a future update may
 * allow the mapping by field number (as field numbers are unique keys).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD })
public @interface ZoomField {

    /**
     * The field name in the Protocol Buffers message.
     */
    String name();

    /**
     * Specified the converter for this field.
     */
    Class<? extends ZoomConvert.Converter<?,?>> converter()
        default ZoomConvert.DefaultConverter.class;

}
