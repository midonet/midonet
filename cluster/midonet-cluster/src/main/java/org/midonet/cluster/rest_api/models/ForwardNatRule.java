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

import java.util.Objects;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.BadRequestHttpException;
import org.midonet.cluster.util.IPAddressUtil;

import static org.midonet.cluster.rest_api.validation.MessageProperty.FRAG_POLICY_INVALID_FOR_L4_RULE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public abstract class ForwardNatRule extends NatRule {

    @ZoomClass(clazz = Topology.Rule.NatTarget.class)
    public static class NatTarget extends ZoomObject {
        @NotNull
        @ZoomField(name = "nw_start", converter = IPAddressUtil.Converter.class)
        public String addressFrom;
        @NotNull
        @ZoomField(name = "nw_end", converter = IPAddressUtil.Converter.class)
        public String addressTo;
        @ZoomField(name = "tp_start")
        public int portFrom;
        @ZoomField(name = "tp_end")
        public int portTo;
    }

    ForwardNatRule(boolean dnat) {
        super(false, dnat);
    }

    @NotNull
    @Size(min = 1)
    @ZoomField(name = "nat_targets")
    public NatTarget[] natTargets = {};

    @JsonIgnore
    public boolean isFloatingIp() {
        return natTargets != null && natTargets.length == 1 &&
               Objects.equals(natTargets[0].addressFrom,
                              natTargets[0].addressTo) &&
               natTargets[0].portFrom == 0 && natTargets[0].portTo == 0;
    }

    @Override
    protected FragmentPolicy getAndValidateFragmentPolicy() {
        boolean unfragmentedOnly = !this.isFloatingIp() || this.hasL4Fields();
        if (this.fragmentPolicy == null) {
            return unfragmentedOnly ? FragmentPolicy.unfragmented
                                    : FragmentPolicy.any;
        }

        FragmentPolicy fp;
        try {
            fp = FragmentPolicy.valueOf(fragmentPolicy.toString());
        } catch (IllegalArgumentException e) {
            // TODO: this his here for backwards compatibility, but we should
            // not be using the MessageProperty.getMessage thing here, nor
            // throwing an HTTP exception.  Instead, throw a custom exception
            // that can be handled (and translated into user facing messages)
            // elsewhere. Same thing below.
            throw new BadRequestHttpException(
                getMessage(FRAG_POLICY_INVALID_FOR_L4_RULE));
        }
        if (unfragmentedOnly && fp != FragmentPolicy.unfragmented) {
            throw new BadRequestHttpException(
                getMessage(FRAG_POLICY_INVALID_FOR_L4_RULE));

        }
        return fp;
    }
}
