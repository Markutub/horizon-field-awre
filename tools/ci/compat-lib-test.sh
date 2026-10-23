#!/usr/bin/env bash
# Unit test for compat-lib.sh (no network). Usage: bash tools/ci/compat-lib-test.sh
set -u
. "$(dirname "$(realpath "$0")")/compat-lib.sh"
F=0; eq() { if [ "$2" = "$3" ]; then echo "PASS $1"; else echo "FAIL $1: got [$2] want [$3]"; F=$((F+1)); fi; }
eq "groups split on space and comma" "$(compat_groups 'sodium sodium+iris,bobby  vulkanmod' | tr '\n' '|')" "sodium|sodium+iris|bobby|vulkanmod|"
eq "empty input yields no groups"   "$(compat_groups '' | wc -l | tr -d ' ')" "0"
eq "name is file-safe"              "$(compat_name 'sodium+iris')" "sodium_iris"
eq "slugs of a group"               "$(compat_slugs 'sodium+iris')" "sodium iris"
compat_slug_ok sodium && eq "good slug" ok ok || eq "good slug" bad ok
compat_slug_ok '../x' && eq "path-like slug refused" accepted refused || eq "path-like slug refused" refused refused
compat_slug_ok '--jvm' && eq "option-like slug refused" accepted refused || eq "option-like slug refused" refused refused
compat_slug_ok 'Sodium' && eq "uppercase refused" accepted refused || eq "uppercase refused" refused refused
compat_is_target iris && eq "iris is a target" ok ok || eq "iris is a target" no ok
compat_is_target cloth-config && eq "dependency is not a target" yes no || eq "dependency is not a target" no no
[ "$F" -eq 0 ] && echo "compat-lib: all passed" || { echo "compat-lib: $F failed"; exit 1; }
