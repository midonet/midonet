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

package org.midonet.client.dto;

import com.google.common.base.Objects;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;

@XmlRootElement
public class DtoSystemState {
    private String state;
    private String availability;
    private String writeVersion;
    private URI uri;

    public String getState() {
        return this.state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public void setAvailability(String availability) {
        this.availability = availability;
    }

    public String getAvailability() {
        return this.availability;
    }

    public String getWriteVersion() {
        return writeVersion;
    }

    public void setWriteVersion(String writeVersion) {
        this.writeVersion = writeVersion;
    }

    public URI getUri() {
        return this.uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    @Override
    public boolean equals(Object other) {

        if (other == this) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        DtoSystemState otherSystemState = (DtoSystemState) other;
        if (!Objects.equal(this.state, otherSystemState.getState())) {
            return false;
        }

        if (!Objects.equal(this.uri, otherSystemState.getUri())) {
            return false;
        }

        if (!Objects.equal(this.availability, otherSystemState.getAvailability())) {
            return false;
        }

        if (!Objects.equal(this.writeVersion,
                           otherSystemState.getWriteVersion())) {
            return false;
        }

        return true;
    }
}
