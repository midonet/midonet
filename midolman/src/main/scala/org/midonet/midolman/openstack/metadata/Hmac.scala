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

package org.midonet.midolman.openstack.metadata

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hmac {
    def hmac(key: Array[Byte], data: Array[Byte]) = {
        val algo = "HmacSHA256"
        // NOTE(yamamoto): The following ":+ 0" is a hack to avoid
        // "java.lang.IllegalArgumentException: Empty key" from
        // SecretKeySpec.  While an empty key is bad for security,
        // it's the default value of nova metadata api, which we
        // need to communicate with.
        val keyspec = new SecretKeySpec(key :+ (0: Byte), 0, key.length, algo)
        val mac = Mac getInstance algo
        mac init keyspec
        mac doFinal data
    }
}
