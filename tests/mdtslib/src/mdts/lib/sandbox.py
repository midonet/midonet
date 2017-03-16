# Copyright 2016 Midokura SARL
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

import logging
import shlex
import subprocess

"""
Helper module to execute commands on the host environment
through docker or sandbox.
"""

LOG = logging.getLogger(__name__)


def remove_container(container):
    p = subprocess.Popen(
            ["docker", "stop", container.get_container_id()],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    stdout, _ = p.communicate()
    LOG.debug(stdout)
    p = subprocess.Popen(
            ["docker", "rm", container.get_container_id()],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    stdout, _ = p.communicate()
    LOG.debug(stdout)


def restart_sandbox(flavour, sandbox_name, override, no_recreate=True):
    cmd = "sandbox-manage -c sandbox.conf run %s " \
          "--name=%s --override=%s " \
          "--force" % (flavour, sandbox_name, override)
    if no_recreate:
        cmd += " --no-recreate"
    p = subprocess.Popen(
            shlex.split(cmd), cwd='../../../',
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    stdout, _ = p.communicate()
    LOG.debug(stdout)
