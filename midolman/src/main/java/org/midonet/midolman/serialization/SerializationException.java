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
package org.midonet.midolman.serialization;

/**
 * Exception class to indicate serialization error
 */
public class SerializationException extends Exception {
    private static final long serialVersionUID = 1L;

    @SuppressWarnings("rawtypes")
    private Class clazz;

    public <T> SerializationException(String msg, Throwable e,
                                        Class<T> clazz) {
        super(msg, e);
        this.clazz = clazz;
    }

    public <T> SerializationException(String msg, Throwable e) {
        super(msg, e);
    }

    @Override
    public String getMessage() {
        return this.clazz + " could not be (de)serialized. " +
                super.getMessage();
    }
}
