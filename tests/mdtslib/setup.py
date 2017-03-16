#!/usr/bin/env python

# Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

from distutils.command import build_py
from distutils.command import clean
from distutils import spawn
import os
import re
import setuptools
import subprocess
import sys

SRC_DIR = "src"
MODULE_NAME = "mdts"

def _git_revcount():
    try:
        return int(subprocess.check_output(["git", "rev-list", "--count",
                                            "HEAD"]))
    except Exception:
        return 0

def _version():
    toplevel = os.path.dirname(
        os.path.dirname(
            os.path.dirname(os.path.abspath(__file__))))
    regex = re.compile('\s*midonetVersion\s*=\s*"([^"]+)"')
    with open('/'.join([toplevel, 'build.gradle'])) as f:
        version = [regex.search(l).group(1) for l in f.readlines()
                   if regex.search(l) is not None]
        if len(version) != 1:
            return "0.0unknown"
        elif version[0].find("-SNAPSHOT") >= 0:
            return version[0].replace("-SNAPSHOT", ".dev%d" % _git_revcount())
        else:
            return version[0].replace("-rc", "rc")

setuptools.setup(name=MODULE_NAME,
                 version=_version(),
                 description="MDTS functional testing",
                 include_package_data=True,
                 package_dir={"": SRC_DIR},
                 packages=setuptools.find_packages(SRC_DIR, exclude=["tests"]),
                 zip_safe=False,
                 license="Apache License, Version 2.0")


