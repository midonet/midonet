/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation used to specify a group name for a specific configuration entry,
 * or an interface containing multiple config entries.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigGroup {

    String value() default "";

}
