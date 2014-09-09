/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.network.validation;


import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import javax.validation.Constraint;
import javax.validation.Payload;

import org.midonet.api.validation.MessageProperty;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({ TYPE, ANNOTATION_TYPE })
@Retention(RUNTIME)
@Constraint(validatedBy = VxlanPortIdIntact.class)
@Documented
public @interface IsVxlanPortIdIntact {

    String message() default MessageProperty.VXLAN_PORT_ID_NOT_SETTABLE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};


}
