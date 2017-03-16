#
# Copyright 2016 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

from docker import Client


class DockerClient(Client):
    def __init__(self, base_url, timeout, sandbox_prefix, sandbox_name):
        super(DockerClient, self).__init__(base_url=base_url,
                                           timeout=timeout, version='auto')
        self.sandbox_prefix = sandbox_prefix
        self.sandbox_name = sandbox_name

    def containers(self, all=None):
        only_sandbox = lambda c: self._is_sandbox_container(c)
        containers = super(DockerClient, self).containers(all=all)
        return filter(only_sandbox, containers)

    def _is_sandbox_container(self, container):
        prefix_len = len(self.sandbox_prefix)
        convert_name = lambda n: n.replace('/', '')[prefix_len:].split('_')[0]
        container_sandbox_names = map(convert_name, container['Names'])
        return self.sandbox_name in container_sandbox_names
