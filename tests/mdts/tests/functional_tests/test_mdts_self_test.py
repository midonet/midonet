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

"""
Does a self test on the mdts
"""
import random

from hamcrest import *
from nose.plugins.attrib import attr
from mdts.services import service

@attr(version="v1.2.0", slow=False)
def test_command_exec_failure():
    """
    mdts.tests.functional_tests.test_mdts_self_test.test_command_exec_failure

    Tests if executing a failing command in a container raises an error
    """
    agents = service.get_all_containers('midolman')
    for agent in agents:
        assert agent.get_service_status() == 'up'

    agent = agents[0]

    cmdline = "echo \"test\""
    result = agent.exec_command(cmdline, stream=False, raise_on_failure=False)
    assert_that(result, equal_to("test"))

    cmdline = "this-command-doesnt-exist-on-a-sane-system --an-option-that-nobody-heard-off"
    result = agent.exec_command(cmdline, stream=False, raise_on_failure=False)
    assert_that(result, equal_to(""))

    cmdline = "echo \"test2\""
    result = agent.exec_command(cmdline, stream=False, raise_on_failure=True)
    assert_that(result, equal_to("test2"))

    cmdline = "this-command-doesnt-exist-on-a-sane-system --an-option-that-nobody-heard-off"
    assert_that(lambda : agent.exec_command(cmdline, stream=False, raise_on_failure=True), raises(RuntimeError))
