/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.brain.services.vladimir.models;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.util.UUIDUtil;

// Doesn't support direct translation to ZOOM
@XmlRootElement
public class Host extends UriResource {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @Nonnull
    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "addresses")
    public List<String> addresses = new ArrayList<>();

    @ZoomField(name = "alive")
    public boolean alive;

    /*
     * From specs: This weight is a non-negative integer whose default
     * value is 1. The MN administrator may set this value to zero to signify
     * that the host should never be chosen as a flooding proxy.
     *
     * Note: though null is not a valid value, we accept it to support clients
     * not providing any value (this will be converted to the proper default
     * value when stored and retrieved afterwards).
     */
    @Min(0)
    @Max(65535)
    @ZoomField(name = "flooding_proxy_weight")
    public Integer floodingProxyWeight = 0;

    @Override
    public String getUri() {
        return ResourceUris.HOSTS;
    }
}
