/*
 * Copyright 2016 Midokura SARL
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

import java.net.URI;
import java.util.UUID;

public class DtoBgpPeer {

    public UUID id;
    public int asNumber;
    public String address;
    public Integer keepAlive;
    public Integer holdTime;
    public Integer connectRetry;
    public String password;

    public URI uri;
    public URI router;

}
