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
Defines common exception classes for MDTS.
"""


class MdtsException(Exception):
    """A base exception class for MDTS."""
    pass


class TestSetupException(MdtsException):
    def __init__(self, testname, exception):
        super(TestSetupException, self).__init__(
            "Setup failed on %s..." % testname,
            exception)


class TestTeardownException(MdtsException):
    def __init__(self, testname, exception):
        super(TestSetupException, self).__init__(
            "Teardown failed on %s..." % testname,
            exception)
