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

import com.google.protobuf.MessageOrBuilder;

import org.midonet.cluster.data.ZoomObject;


public abstract class RelativeUriResource extends ZoomObject {

    private URI parentUri = null;

    /**
     * Default constructor
     */
    public RelativeUriResource() {
    }

    /**
     * Protobuf-conversion constructor.
     */
    public RelativeUriResource(MessageOrBuilder proto) {
        super(proto);
    }

    /**
     * @return the parentUri
     */
    @XmlTransient
    public URI getParentUri() {
        return parentUri;
    }

    /**
     * @param parentUri
     *            the parentUri to set
     */
    public void setParentUri(URI parentUri) {
        this.parentUri = parentUri;
    }

    /**
     * @return URI of the resource.
     */
    public abstract URI getUri();
}
