#!/usr/bin/env python

# Copyright 2014 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import argparse
import paramiko
import os.path

ssh = None

def get_ssh(repo_host):
    global ssh
    if ssh is None:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(repo_host, username='midokura')
    return ssh


# copy packages to the apt repository host
def copy(**kwargs):
    ssh = get_ssh(kwargs['repo_host'])
    ftp = ssh.open_sftp()
    archive_dir = kwargs['repo_path'] + "/archive/"

    for p in kwargs['packages']:
        pname = os.path.basename(p)
        target = archive_dir + pname
        print 'putting file {package} to {repo_host}:{target}'.format(
           package=p, target=target, repo_host=kwargs['repo_host'])
        ftp.put(p, target)


# put packages in the apt repository
def put_on_apt(**kwargs):
    ssh = get_ssh(kwargs['repo_host'])
    for p in kwargs['packages']:
        pname = os.path.basename(p)
        cmd = 'cd {repo} && reprepro includedeb {codename} '\
              ' ./archive/{package}'.format(repo=kwargs['repo_path'],
               codename=kwargs['codename'], package=pname)

        print "executing command: ", cmd
        in_, out, err = ssh.exec_command(cmd)
        in_.write(kwargs['passphrase'] + '\n')
        in_.write(kwargs['passphrase'] + '\n')
        in_.flush()
        print "---stdout---"
        print out.read()
        print "---stderr---"
        print err.read()
        print "------------"


def main(**kwargs):
    copy(**kwargs)
    put_on_apt(**kwargs)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='put up deb packages')
    parser.add_argument('packages', metavar='P', type=str, nargs='+',
                       help='list of path to packages to deploy')
    parser.add_argument('-r', metavar='Repo', help='repo path', required=True)
    parser.add_argument('-c', metavar='Codename', required=True,
                        help='Ubuntu codename of underlying platform')
    parser.add_argument('-p', metavar='passphrase', help='GPG passphrase',
                         required=True)
    args = parser.parse_args()

    repo_host = args.r.split(':')[0]
    repo_path = args.r.split(':')[1]
    kwargs = {'repo_host': repo_host, 'repo_path': repo_path, 'codename': args.c,
               'passphrase' : args.p, 'packages': args.packages}
    main(**kwargs)
