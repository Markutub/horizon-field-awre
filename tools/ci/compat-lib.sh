# Helpers for the opt-in compat client runs in integration.sh (sourced, not executed). Pure shell, unit-tested by compat-lib-test.sh.
# COMPAT_MODS grammar: groups separated by space or comma; a group is mod slugs joined with '+' (one client is started per group).
#   COMPAT_MODS="sodium sodium+iris,bobby vulkanmod"  ->  groups: sodium | sodium+iris | bobby | vulkanmod
compat_groups() { local s=${1//,/ } g; for g in $s; do [ -n "$g" ] && echo "$g"; done; return 0; }
compat_name()   { echo "${1//+/_}"; }                       # file-name safe group name
compat_slug_ok() { [[ "$1" =~ ^[a-z0-9][a-z0-9_-]*$ ]]; }   # Modrinth slugs; refuses anything that could be an option or path
compat_slugs()  { echo "${1//+/ }"; }
# slugs AWRE logs as "compat target loaded: <id>" (the Fabric mod id equals the slug for these)
compat_is_target() { case "$1" in sodium|iris|vulkanmod|bobby) return 0;; *) return 1;; esac; }
