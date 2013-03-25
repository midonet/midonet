/*
 * Copyright 2012 Midokura PTE LTD.
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
@Constraint(validatedBy = MacPortConstraintValidator.class)
@Documented
public @interface MacPortValid {

    String message() default MessageProperty.MAC_PORT_ON_BRIDGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
