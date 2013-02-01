/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network.validation;

import org.midonet.api.validation.MessageProperty;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({ TYPE, ANNOTATION_TYPE })
@Retention(RUNTIME)
@Constraint(validatedBy = PortGroupNameConstraintValidator.class)
@Documented
public @interface IsUniquePortGroupName {

    String message() default MessageProperty.IS_UNIQUE_PORT_GROUP_NAME;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
