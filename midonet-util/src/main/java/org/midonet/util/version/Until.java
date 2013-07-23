/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.util.version;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Annotation to denote version that a given field is valid Since.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.METHOD })
public @interface Until {
    String value() default "";
}
