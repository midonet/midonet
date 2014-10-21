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

#! /usr/bin/python

import argparse
import gzip
import os
import pdb
import sys
import time

from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText

KNOWN_CONTENT_TYPES = [
    'text/x-include-once-url',
    'text/x-include-url',
    'text/cloud-config-archive',
    'text/upstart-job',
    'text/cloud-config',
    'text/part-handler',
    'text/x-shellscript',
    'text/cloud-boothook',
]

def file_content_type(text):
    filepath, content_type = text.split(":", 1)
    filename = os.path.basename(filepath)
    return (open(filepath, 'r'), filename, content_type.strip())

def make_mime(files):
    sub_messages = []
    for i, (fh, filename, format_type) in \
            enumerate(map(file_content_type, files)):
        contents = fh.read()
        sub_message = MIMEText(contents, format_type, sys.getdefaultencoding())
        sub_message.add_header('Content-Disposition',
                               'attachment; filename="%s"' % (filename))
        content_type = sub_message.get_content_type().lower()
        if content_type not in KNOWN_CONTENT_TYPES:
            raise (("WARNING: content type %r for attachment %s "
                    "may be incorrect!\n") % (content_type, i + 1))
        sub_messages.append(sub_message)

    combined_message = MIMEMultipart()
    for msg in sub_messages:
        combined_message.attach(msg)

    return str(combined_message)

def get_userdata(filepath):
    with gzip.open(filepath, 'wb') as g:
        g.writelines(make_mime(['init:x-shellscript']))
    with open(filepath, 'rb') as f:
        return f.read()

argparse = argparse.ArgumentParser()
argparse.add_argument('-o', '--output', metavar='path', default='-')
argparse.add_argument('-z', '--compress',  action='store_true')
argparse.add_argument('part', nargs='*', default=['init:x-shellscript'])
args = argparse.parse_args()

mime = make_mime(args.part)
if args.output == '-':
    # ignore compress flag
    print mime
else:
    if args.compress:
        with gzip.open(args.output, 'wb') as g:
            g.writelines(mime)
    else:
        with open(args.output, 'wb') as f:
            f.writelines(mime)
