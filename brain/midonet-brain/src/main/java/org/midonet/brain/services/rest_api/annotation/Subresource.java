package org.midonet.brain.services.rest_api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD })
public @interface Subresource {

    enum Type {
        REFERENCED,
        EMBEDDED
    }

    String path();
    Type type() default Type.REFERENCED;
}
