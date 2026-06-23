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

# 1. Find the XCLocalSwiftPackageReference object ID for the local package (relativePath = ..)
ref_match = re.search(
    r'(/\* XCLocalSwiftPackageReference "\.\." \*/ = \{[^}]*?\bisa = XCLocalSwiftPackageReference\b[^}]*?\})',
    text, re.DOTALL
)
if not ref_match:
    sys.exit("ERROR: Could not find XCLocalSwiftPackageReference for '..' in pbxproj")

# Extract the hex object ID that precedes the block comment
id_match = re.search(
    r'\b([0-9A-Fa-f]{24})\s+/\* XCLocalSwiftPackageReference "\.\." \*/',
    text
)
if not id_match:
    sys.exit("ERROR: Could not extract object ID for XCLocalSwiftPackageReference")

ref_id = id_match.group(1)
print(f"  XCLocalSwiftPackageReference id = {ref_id}")

# 2. Locate the XCSwiftPackageProductDependency block for productName = StreamProbe
dep_pattern = re.compile(
    r'(/\* StreamProbe \*/ = \{\s*isa = XCSwiftPackageProductDependency;)(.*?)(productName = StreamProbe;)',
    re.DOTALL
)

dep_match = dep_pattern.search(text)
if not dep_match:
    sys.exit("ERROR: Could not find XCSwiftPackageProductDependency for StreamProbe")

full_block = dep_match.group(0)

# 3. Check whether the key is already present
if f"package = {ref_id}" in full_block:
    print("  package key already present — nothing to do.")
    sys.exit(0)

# 4. Re-inject: insert `package = <id> /* … */;` right after the `isa` line
injected = dep_pattern.sub(
    lambda m: (
        m.group(1)
        + f"\n\t\t\tpackage = {ref_id} /* XCLocalSwiftPackageReference \"..\" */;"
        + m.group(2)
        + m.group(3)
    ),
    text,
    count=1
)

with open(path, "w") as f:
    f.write(injected)

print(f"  Injected: package = {ref_id} /* XCLocalSwiftPackageReference \"..\" */;")
PYEOF

echo "✓ iosApp.xcodeproj regenerated and patched successfully."
