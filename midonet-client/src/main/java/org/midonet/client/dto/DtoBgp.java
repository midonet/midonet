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

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/28/11
 * Time: 3:33 PM
 */
@XmlRootElement
public class DtoBgp {
    private UUID id = null;
    private int localAS;
    private int peerAS;
    private String peerAddr = null;
    private URI adRoutes;
    private URI uri;

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public int getLocalAS() {
        return localAS;
    }

    public void setLocalAS(int localAS) {
        this.localAS = localAS;
    }

    public int getPeerAS() {
        return peerAS;
    }

    public void setPeerAS(int peerAS) {
        this.peerAS = peerAS;
    }

    public String getPeerAddr() {
        return peerAddr;
    }

    public void setPeerAddr(String peerAddr) {
        this.peerAddr = peerAddr;
    }

    public URI getAdRoutes() {
        return adRoutes;
    }

    public void setAdRoutes(URI adRoutes) {
        this.adRoutes = adRoutes;
    }
}
