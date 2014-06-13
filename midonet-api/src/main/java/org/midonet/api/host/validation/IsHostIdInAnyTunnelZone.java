/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.api.host.validation;

import org.midonet.api.validation.MessageProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import javax.validation.Constraint;
import javax.validation.Payload;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


@Target({ METHOD, FIELD, ANNOTATION_TYPE })
@Retention(RUNTIME)
@Constraint(validatedBy = HostIdInTunnelZonesValidator.class)
@Documented
public @interface IsHostIdInAnyTunnelZone {
    String message() default MessageProperty.HOST_IS_NOT_IN_ANY_TUNNEL_ZONE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
