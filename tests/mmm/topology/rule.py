# Copyright 2014 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from topology.resource_base import ResourceBase

class Rule(ResourceBase):

    def __init__(self,attrs):
        pass

class RuleMasq(Rule):

    def __init__(self,attrs):
        super(RuleMasq, self).__init__(attrs)
        self.snat_ip = attrs['snat_ip']

class RuleFloatIP(Rule):

    def __init__(self,attrs):
        super(RuleFloatIP, self).__init__(attrs)

        self.fixed_ip = attrs['fixed_ip']
        self.float_ip = attrs['float_ip']

class RuleTransProxy(Rule):

    def __init__(self,attrs):
        super(RuleTransProxy, self).__init__(attrs)

        self.fixed_ip = attrs['fixed_ip']
        self.float_ip = attrs['float_ip']


