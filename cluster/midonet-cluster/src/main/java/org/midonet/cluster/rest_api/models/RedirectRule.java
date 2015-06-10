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

package org.midonet.cluster.rest_api.models;

import java.util.UUID;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomOneOf;
import org.midonet.cluster.util.UUIDUtil;

@ZoomOneOf(name = "redir_rule_data")
public class RedirectRule extends Rule {

    @ZoomField(name = "target_port", converter = UUIDUtil.Converter.class)
    public UUID targetPort;
    @ZoomField(name = "ingress")
    public boolean ingress;
    @ZoomField(name = "fail_open")
    public boolean failOpen;

    public RedirectRule() {
        super(RuleType.REDIRECT, RuleAction.REDIRECT);
    }

    @Override
    public String getType() {
        return Rule.Redirect;
    }

}
