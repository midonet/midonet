#!/bin/bash

perftests=./misc/perf/perftests.sh

$perftests basic gateway-4threads
$perftests basic gateway-2threads
$perftests basic compute

$perftests l2gw gateway-4threads
$perftests l2gw gateway-2threads
$perftests l2gw compute

$perftests l3_basic gateway-4threads
$perftests l3_basic gateway-2threads
$perftests l3_basic compute

$perftests l3_deep_trie gateway-4threads
$perftests l3_deep_trie gateway-2threads
$perftests l3_deep_trie compute
