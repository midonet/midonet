#!/usr/bin/python

# ======
# Usage:
# ======
#
# ./change_log <release_version> <previous_version>
#
# The release_version and previous_version can be any valid Git identifier
# (commit hash, branch name, tag). In general for releases we would use
# the name of the stable branches.
#
# Before running the credentials for the Jira user that is running the script
# need to be added. This is needed to be able to access the Jira REST API and
# get the information of the Jira issue that corresponds to a certain change
# in the repo.
#
# The credentials are added as env variables called JIRA_USERNAME and
# JIRA_PASSWORD. These can be added by doing an:
#
# export JIRA_USERNAME=username
# export JIRA_PASSWORD=password
#
# These commands can be run on the terminal or added to a file and sourcing
# that file later.
#
# The script will find all changes made between the two versions, and parse all
# commit messages for references. Each commit message can have any number of
# refs in different lines, with the regex "Ref: MI-[1-9]+".
# For each ref found the issue will be fetched from the Jira REST API to get
# the corresponding information.
#
# =======
# Output:
# =======
#
# Running this script will output a list of changes that were made following
# this pattern:
#
# First we print which epics (and stories and tasks related to it) were worked
# on in between these two releases.
#
# == <epic summary> (<epic jira key>) ==
# <epic description>
#
# <story jira key>: <story summary>
#     <task jira key>: <task summary>
#     <task description>
#
#     <task jira key>: <task summary>
#     ...
#
# Afterwards we print all bugs that were worked on:
#
# <bug jira key>: <bug summary>
# ...
#
# Finally we print the commits that had no reference to any Jira issue:
#
# Minor change, commit: <commit_hash>
# <commit message>
# ...

import base64
from collections import defaultdict
import json
import os
import re
import subprocess
import sys
import urllib2

jira_base_url = 'https://midobugs.atlassian.net/'
jira_web_url = jira_base_url + 'browse/'
jira_api_url = jira_base_url + 'rest/api/2/'
jira_issues = jira_api_url + 'issue/'

epic_link_key = 'customfield_10008'
no_epic = 'no_epic'
no_story = 'no_story'

username_env_key = 'JIRA_USERNAME'
password_env_key = 'JIRA_PASSWORD'
username = os.environ.get(username_env_key)
password = os.environ.get(password_env_key)

if username is None:
    print('{} env variable needs to be defined.'.format(username_env_key))
    exit(1)

if password is None:
    print('{} env variable needs to be defined.'.format(password_env_key))
    exit(1)

def get_issue(issue):
    request = urllib2.Request(jira_issues + issue)
    base64string = base64.b64encode(username + ":" + password)
    request.add_header("Authorization", "Basic " + base64string)
    return json.load(urllib2.urlopen(request))

def is_merge(commit):
    res = subprocess.check_output(['git', 'cat-file', '-p', commit]).splitlines()
    parents = [x for x in res if x.startswith('parent')]
    return len(parents) > 1

def get_issues_and_commits(since, until):
    issues = []
    commits = []
    changes = subprocess.check_output(['git', 'log', '--format=%h', since, '^' + until]).splitlines()
    for commit in changes:
        commit_msg = subprocess.check_output(['git', 'show', '-s', '--format=%B', commit])
        issues_matched = re.findall('Ref: MI-[0-9]+', commit_msg)

        if issues_matched:
            for issue in [i.split()[1] for i in issues_matched]:
                issue_data = get_issue(issue)
                if issue_not_in(issue_data, issues):
                    issues.append(issue_data)
        elif not is_merge(commit): # discard merges
            commits.append((commit, commit_msg))
    return issues, commits

def get_story_from_issue(issue_data):
    if is_story(issue_data):
        return issue_data['key']

    links = issue_data['fields']['issuelinks']

    if links:
        for link in links:
            blocks = link['type']['name'] == 'Blocks'
            blocks_story = blocks and link['outwardIssue']['fields']['issuetype']['name'] == 'Story'

            if blocks_story:
                return link['outwardIssue']['key']

    return no_story

def issue_not_in(issue, issue_list):
    return issue['id'] not in [i['id'] for i in issue_list]

def is_story(issue):
    return issue['fields']['issuetype']['name'] == 'Story'

# Returns a (epic_id -> story_id -> issue_list of that epic) dictionary
def digest_issues(all_issues):
    issues = defaultdict(lambda: defaultdict(list))
    for issue in all_issues:
        epic = issue['fields'][epic_link_key] or no_epic
        story = get_story_from_issue(issue)

        if issue_not_in(issue, issues[epic][story]):
            issues[epic][story].append(issue)
    return issues

def print_issue(issue_data):
    print('    {}: {}'.format(issue_data['key'], issue_data['fields']['summary'].strip(' \t\n\r')))

def print_commit(commit, commit_msg):
    print('Minor change, commit: ' + commit)
    print(commit_msg.strip(' \t\n\r'))

def print_line(separator):
    print(separator * 50)

def print_change_log():
    if len(sys.argv) != 3:
        print("Usage: {} <release_version> <previous_version>".format(sys.argv[0]))
        exit(1)

    release_version = sys.argv[1]
    previous_version = sys.argv[2]

    issues, commits = get_issues_and_commits(release_version, previous_version)

    bugs = [issue for issue in issues if issue['fields']['issuetype']['name'] == 'Bug']
    rest = [issue for issue in issues if issue['fields']['issuetype']['name'] != 'Bug']

    # issues
    epic_issues = digest_issues(rest)

    if epic_issues:
        print_line('#')
        print('Issues')
        print_line('#')

        for epic, story_issues in epic_issues.iteritems():
            print('')
            if epic == no_epic:
                print('== Changes with no epic ==')
            else:
                epic_fields = get_issue(epic)['fields']
                print('== {} ({}) =='.format(epic_fields['summary'], epic))
                print(epic_fields['description'] or 'No description.')
            for story, issue_list in story_issues.iteritems():
                print('')
                if (story == no_story):
                    print('Changes with no story')
                else:
                    story_summary = get_issue(story)['fields']['summary']
                    print('{}: {}'.format(story, story_summary))
                for issue in issue_list:
                    print_issue(issue)

    # bugs
    if bugs:
        print('')
        print_line('#')
        print('Bugs')
        print_line('#')
        print('')

    for bug in bugs:
        print_issue(bug)

    # commits with no reference
    if commits:
        print('')
        print_line('#')
        print('Commits with no Jira reference')
        print_line('#')
        print('')

        for commit, commit_msg in commits:
            print_commit(commit, commit_msg)
            print_line('-')

if __name__ == "__main__":
    print_change_log()