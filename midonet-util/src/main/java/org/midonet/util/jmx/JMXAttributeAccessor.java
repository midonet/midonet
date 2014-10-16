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
package org.midonet.util.jmx;

import java.io.IOException;
import java.util.Hashtable;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;

/**
 * Simple wrapper over a remote JMX bean. Can get a value in a compile-time type
 * checked way.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/16/12
 */
public class JMXAttributeAccessor<T> {

    private final MBeanServerConnection connection;
    private final ObjectName name;
    private final String attributeName;

    private JMXAttributeAccessor(MBeanServerConnection connection,
                                 ObjectName name, String attributeName) {
        this.connection = connection;
        this.name = name;
        this.attributeName = attributeName;
    }

    public T getValue()
        throws InstanceNotFoundException, IOException, ReflectionException,
               AttributeNotFoundException, MBeanException {
        @SuppressWarnings("unchecked")
        T val = (T) connection.getAttribute(name, attributeName); // unsafe
        return val;
    }

    public <T> T getValue(Class<T> clazz)
        throws InstanceNotFoundException, IOException, ReflectionException,
               AttributeNotFoundException, MBeanException {
        return clazz.cast(connection.getAttribute(name, attributeName));
    }

    public static class Builder<T> {

        String nameDomain;
        Hashtable<String, String> nameProperties = new Hashtable<String, String>();

        private MBeanServerConnection connection;
        private String attributeName;

        public Builder(MBeanServerConnection connection, String attributeName) {
            this.connection = connection;
            this.attributeName = attributeName;
        }

        public Builder<T> withNameDomain(Class<?> domainNameClass) {
            this.nameDomain = domainNameClass.getPackage().getName();
            return this;
        }

        public Builder<T> withDomainName(String domainName) {
            this.nameDomain = domainName;
            return this;
        }

        public Builder<T> withNameKey(String keyName, Class<?> keyValueClass) {
            nameProperties.put(keyName, keyValueClass.getSimpleName());
            return this;
        }

        public Builder<T> withNameKey(String keyName, Object object) {
            nameProperties.put(keyName, object.toString());
            return this;
        }

        public JMXAttributeAccessor<T> build()
            throws MalformedObjectNameException {

            ObjectName name = new ObjectName(nameDomain, nameProperties);

            return new JMXAttributeAccessor<T>(connection, name, attributeName);
        }
    }
}
