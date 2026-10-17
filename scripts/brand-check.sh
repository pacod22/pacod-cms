#!/usr/bin/env bash
#
# Refuses any text file in the repository that carries a word from a private deny-list -- names
# that must never be published, for example.
#
# The list is deliberately kept outside the repository: a guard that listed every word it forbids
# would publish them itself. It lives at .local/brand-tokens.txt (git-ignored), one extended
# regular expression per line, matched case-insensitively. Without the file the check is skipped
# and says so, so a fresh clone still builds.
#
#   scripts/brand-check.sh                 # uses .local/brand-tokens.txt
#   BRAND_TOKENS=/path/to/list scripts/brand-check.sh
#
# Exit status: 0 clean or skipped, 1 a token was found, 2 usage error.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOKENS="${BRAND_TOKENS:-$ROOT/.local/brand-tokens.txt}"

if [[ ! -f "$TOKENS" ]]; then
    echo "brand-check: skipped, no token list at $TOKENS"
    exit 0
fi

# Case-insensitive matching of Cyrillic, Greek and CJK needs a UTF-8 locale.
export LC_ALL=en_US.UTF-8

cd "$ROOT"

# Every file git would consider (tracked or not yet added, ignored files excluded), or, outside a
# repository, everything except build output, local data and IDE state.
list_files() {
    if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        git ls-files -z --cached --others --exclude-standard
    else
        find . \( -name target -o -name .data -o -name .idea -o -name .git -o -name .local \
                  -o -name .artifacts -o -name node_modules \) -prune -o -type f -print0
    fi
}

# Drop empty lines and comments from the list; grep -f would match everything on an empty pattern.
PATTERNS="$(mktemp)"
trap 'rm -f "$PATTERNS"' EXIT
grep -v -e '^[[:space:]]*$' -e '^#' "$TOKENS" > "$PATTERNS" || true
if [[ ! -s "$PATTERNS" ]]; then
    echo "brand-check: token list $TOKENS is empty" >&2
    exit 2
fi

# -I skips binary files (images, the vendored TinyMCE fonts), -H names the file, -n the line.
if list_files | xargs -0 grep -I -H -n -i -E -f "$PATTERNS" -- 2>/dev/null; then
    echo "brand-check: FAILED, the lines above carry a forbidden token" >&2
    exit 1
fi

echo "brand-check: clean"
