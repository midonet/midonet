/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.network.validation;

import com.midokura.midonet.api.validation.MessageProperty;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({ TYPE, ANNOTATION_TYPE })
@Retention(RUNTIME)
@Constraint(validatedBy = PortsLinkableConstraintValidator.class)
@Documented
public @interface PortsLinkable {

    String message() default MessageProperty.PORTS_LINKABLE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
