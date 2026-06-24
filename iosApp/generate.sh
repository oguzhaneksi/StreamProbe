#!/bin/bash
# generate.sh — regenerate the iosApp Xcode project via XcodeGen and re-apply a patch that
# XcodeGen 2.45.4 drops: the `package = <XCLocalSwiftPackageReference-id>` key inside the
# XCSwiftPackageProductDependency block for the local StreamProbe package.  Without this key
# the linker cannot find the product even though the project file looks correct otherwise.
#
# Usage: run from anywhere; the script cd-s into iosApp/ before calling xcodegen.
#   ./iosApp/generate.sh
# or equivalently (from inside iosApp/):
#   ./generate.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PBXPROJ="$SCRIPT_DIR/iosApp.xcodeproj/project.pbxproj"

echo "→ Running xcodegen generate in $SCRIPT_DIR …"
(cd "$SCRIPT_DIR" && xcodegen generate)

echo "→ Checking XCSwiftPackageProductDependency patch …"

python3 - "$PBXPROJ" <<'PYEOF'
import sys, re

path = sys.argv[1]
with open(path, "r") as f:
    text = f.read()

# 1. Verify the XCLocalSwiftPackageReference block for the local package exists (fail loudly if not).
if not re.search(
    r'XCLocalSwiftPackageReference "\.\."[^}]*\bisa = XCLocalSwiftPackageReference\b',
    text, re.DOTALL
):
    sys.exit("ERROR: Could not find XCLocalSwiftPackageReference for '..' in pbxproj")

# Extract the hex object ID that precedes the block comment.
id_match = re.search(
    r'\b([0-9A-Fa-f]{24})\s+/\* XCLocalSwiftPackageReference "\.\." \*/',
    text
)
if not id_match:
    sys.exit("ERROR: Could not extract object ID for XCLocalSwiftPackageReference")

ref_id = id_match.group(1)
print(f"  XCLocalSwiftPackageReference id = {ref_id}")

# 2. Locate the XCSwiftPackageProductDependency block for productName = StreamProbe.
dep_pattern = re.compile(
    r'(/\* StreamProbe \*/ = \{\s*isa = XCSwiftPackageProductDependency;)(.*?)(productName = StreamProbe;)',
    re.DOTALL
)

dep_match = dep_pattern.search(text)
if not dep_match:
    sys.exit("ERROR: Could not find XCSwiftPackageProductDependency for StreamProbe")

# 3. Check whether the correct key is already present (idempotency fast-path).
full_block = dep_match.group(0)
if f"package = {ref_id}" in full_block:
    print("  package key already present — nothing to do.")
    sys.exit(0)

# 4. Strip any stale `package = <hex> /* XCLocalSwiftPackageReference ".." */;` line within
#    the dependency block before re-injecting — makes the script idempotent even if XcodeGen
#    rotates the object ID between runs (prevents a malformed double-`package` pbxproj).
def patch_block(m):
    inner = m.group(2)
    inner = re.sub(
        r'\n\t\t\tpackage = [0-9A-Fa-f]{24} /\* XCLocalSwiftPackageReference "\.\." \*/;',
        '',
        inner
    )
    return (
        m.group(1)
        + f"\n\t\t\tpackage = {ref_id} /* XCLocalSwiftPackageReference \"..\" */;"
        + inner
        + m.group(3)
    )

injected = dep_pattern.sub(patch_block, text, count=1)

with open(path, "w") as f:
    f.write(injected)

print(f"  Injected: package = {ref_id} /* XCLocalSwiftPackageReference \"..\" */;")
PYEOF

echo "✓ iosApp.xcodeproj regenerated and patched successfully."
