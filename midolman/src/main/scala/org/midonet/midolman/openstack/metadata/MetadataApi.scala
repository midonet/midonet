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

/*
 * Metadata service API definition
 *
 * http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-metadata.html
 * http://docs.openstack.org/admin-guide-cloud/compute-networking-nova.html#metadata-service
 */
object MetadataApi {
    val Address = "169.254.169.254"
    val Port = 80
}
