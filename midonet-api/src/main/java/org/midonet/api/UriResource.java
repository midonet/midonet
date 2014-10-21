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

package org.midonet.api;

import java.net.URI;

import javax.xml.bind.annotation.XmlTransient;

public abstract class UriResource {

    private URI baseUri = null;

    /**
     * Default constructor
     */
    public UriResource() {
    }

    /**
     * Constructor
     *
     * @param baseUri
     *            The base URI to construct all the URIs from.
     */
    public UriResource(URI baseUri) {
        setBaseUri(baseUri);
    }

    /**
     * @return the baseUri
     */
    @XmlTransient
    public URI getBaseUri() {
        return baseUri;
    }

    /**
     * @param baseUri
     *            the baseUri to set
     */
    public void setBaseUri(URI baseUri) {
        this.baseUri = baseUri;
    }

    /**
     * @return URI of the resource.
     */
    public URI getUri() {
        // Override this method.
        return null;
    }
}
