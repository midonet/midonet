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

# terminal multiplexer tool setting: tmux os screen(default)
MMM_TM=${MMM_TM:-screen}


tm_run() {
    main_name=$1 # session name in screen, window name in tmux
    sub_name=$2  # window name in screen, pane name in tmux
    shift 2
    cmdline=$*

    if [ $MMM_TM == "screen" ]; then
        screen_run $main_name $sub_name "$cmdline"
    elif [ $MMM_TM == "tmux" ]; then
        tmux_run $main_name $sub_name "$cmdline"
    else
        echo "Unsupported terminal multiplexer " $MMM_TM
        echo "aborting"
        exit 1
    fi

}


tm_stop() {
    main_name=$1 # session name in screen, window name in tmux
    sub_name=$2  # window name in screen, pane name in tmux
    if [ $MMM_TM == "screen" ]; then
        screen_stop $main_name $sub_name
    elif [ $MMM_TM == "tmux" ]; then
        tmux_stop $main_name
    else
        echo "Unsupported terminal multiplexer " $MMM_TM
        echo "aborting"
        exit 1
    fi
}


screen_run() {
    session_name=$1
    window_name=$2
    shift 2
    cmdline=$*

    if ! screen -ls |grep -q -w $session_name; then
        screen -d -m -S $session_name  -t $window_name sh -c "$cmdline"
    else
        screen -S $session_name -X screen -t $window_name sh -c "$cmdline"
    fi
}


screen_stop() {
    session_name=$1
    window_name=$2
    if test -n "$window_name"; then
        screen -S $session_name -X select "$window_name"
        screen -S $session_name -X kill
    else
        screen -S $session_name -X quit
    fi
}


TMUX_SESSION_NAME=mmm


tmux_run() {
    window_name=$1
    pane_name=$2
    shift 2
    cmdline=$*
    is_new_window=false

    if ! tmux has-session -t $TMUX_SESSION_NAME 2>/dev/null; then
        # create a scratch window
        tmux new-session -d -s $TMUX_SESSION_NAME -n "scratch"
        tmux send-keys -t $TMUX_SESSION_NAME:scratch  cd ' ' ../midolman $'\n'

        # create window for mdts testing
        tmux new-window -t $TMUX_SESSION_NAME -n test
        tmux select-window -t $TMUX_SESSION_NAME:test
        tmux send-keys -t $TMUX_SESSION_NAME:test  cd ' ' ../../../mdts/tests/functional_tests  $'\n'
    fi

    if ! tmux select-window -t $TMUX_SESSION_NAME:$window_name 2> /dev/null; then
        tmux new-window -t $TMUX_SESSION_NAME -n $window_name
        is_new_window=true
    fi
    if ! $is_new_window; then
        if ! tmux split-window -t $TMUX_SESSION_NAME:$window_name; then
            tmux split-window -t $TMUX_SESSION_NAME:$window_name
        fi
    fi

    tmux select-layout -t $TMUX_SESSION_NAME:$window_name tiled

    tmux send-keys -t $TMUX_SESSION_NAME:$window_name  printf ' '\' '\033]2;'$pane_name'\033\\' \' $'\n'

    tmux send-keys -t $TMUX_SESSION_NAME:$window_name "$cmdline" $'\n'

}


tmux_stop() {
    window_name=$1
    pane_name=$2
    if test -n "$pane_name"; then
        tmux kill-pane -t $TMUX_SESSION_NAME:$window_name.$pane_name 2> /dev/null
    else
        tmux kill-window -t $TMUX_SESSION_NAME:$window_name 2> /dev/null
    fi
}
