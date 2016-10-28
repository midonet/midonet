#
# Copyright 2015 Midokura SARL
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
from jpype import *
import logging

LOG = logging.getLogger(__name__)

_jvm_initialized = False

def initjvm():
    global _jvm_initialized
    if (not _jvm_initialized):
        LOG.debug("Starting JVM...")
        startJVM(getDefaultJVMPath(), "-ea")
        java.lang.System.out.println("JVM Running")
        _jvm_initialized = True