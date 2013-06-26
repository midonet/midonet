/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.midolman.version.guice;

import com.google.inject.BindingAnnotation;
import java.lang.annotation.Target;
import java.lang.annotation.Retention;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;

@BindingAnnotation @Target({ FIELD, PARAMETER, METHOD }) @Retention(RUNTIME)
/**
 * Annotation used to indicate version check.  This annotation interface is
 * used specifically for Guice injection.
 */
public @interface VerCheck {}
