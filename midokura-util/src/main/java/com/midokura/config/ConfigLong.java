/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that should be applied on the methods of interfaces meant to be
 * used as a configuration facade. It adds <code>type</code>, <code>group</code>,
 * <code>key</code> and <code>defaultValue</code> information to the method.
 */
@Documented
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigLong {

    String key() default "";

    long defaultValue() default 0;
}
