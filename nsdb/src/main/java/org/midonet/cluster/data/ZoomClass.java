/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.cluster.data;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.google.protobuf.MessageOrBuilder;

/**
 * The annotation specifies how a Java class should be converted to an
 * equivalent Protocol Buffers message.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE })
public @interface ZoomClass {

    /**
     * Specifies the corresponding Protocol Buffers class for this type.
     */
    Class<? extends MessageOrBuilder> clazz();

    /**
     * Specifies the converter for this class. Objects to types annotated with
     * the ZoomClass annotation, will use this converter unless overridden by
     * a different converter with the ZoomFiled annotation.
     */
    Class<? extends ZoomConvert.Converter<?,?>> converter()
        default ZoomConvert.ObjectConverter.class;

    /**
     * Specifies the factory for this class. The factory must return the Java
     * type for a given Protocol Buffers messages, such that the converter
     * can distinguish between different Java classes mapped to the same
     * Protocol Buffer message.
     */
    Class<? extends ZoomConvert.Factory<?,?>> factory()
        default ZoomConvert.DefaultFactory.class;

}
