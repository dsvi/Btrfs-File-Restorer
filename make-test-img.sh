#!/usr/bin/env bash
#
# make-test-img.sh — generates test.img, the btrfs filesystem image used by
# ServerTest (src/test/kotlin/ServerTest.kt).
#
# What the test needs from the image:
#   * a btrfs filesystem (btrfs-progs refuses devices < ~109 MiB);
#   * the exact path the test restores (see ServerTest.kt):
#       /Tomb raider па рюске!/А́а́, Е́е́, И́и́, О́о́, У́у́, Ы́ы́, Э́э́, Ю́ю́, Я́я́.txt
#   * the owner of every restored inode must be the *current* user, because
#     the test runs `btrfs restore --metadata ...` unprivileged, which chowns
#     the restored files to their original owner — that chown only succeeds
#     without root if the original owner is the user running the restore;
#   * only user.* xattrs (trusted./security. ones cannot be restored
#     unprivileged either).
#
# This machine does not have root, so mkfs/mount are done inside a
# privileged docker container (docker group membership is enough).
# A small helper image (debian + btrfs-progs) is built for the job; on exit
# the script cleans up after itself: helper image, the docker build cache,
# and the base image too if nothing else uses it.
#
# Usage:
#   ./make-test-img.sh [OUTPUT_IMAGE] [SIZE]
#       OUTPUT_IMAGE   default: <script dir>/test.img
#       SIZE           default: 256M (btrfs minimum is ~109 MiB)
#
# The script also runs a host-side sanity check that reproduces exactly what
# ServerTest does (dry-run listing + `btrfs restore --metadata --xattr
# --symlinks --path-regex ...`), so a broken image fails loudly here
# instead of inside the test.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-${SCRIPT_DIR}/test.img}"
SIZE="${2:-256M}"
FS_UID="$(id -u)"
FS_GID="$(id -g)"
MAKER_IMAGE="btrfs-img-maker:local"
BASE_IMAGE="debian:bookworm"   # base the helper image is built on (cleaned up if unused)
TMPD=""   # set later by the sanity check; cleaned up by cleanup()

# The path ServerTest recovers. Must match src/test/kotlin/ServerTest.kt.
TOMB_DIR='Tomb raider па рюске!'
TARGET_FILE='А́а́, Е́е́, И́и́, О́о́, У́у́, Ы́ы́, Э́э́, Ю́ю́, Я́я́.txt'
TARGET_PATH="/${TOMB_DIR}/${TARGET_FILE}"

command -v docker >/dev/null 2>&1 || {
    echo "error: docker is required (mkfs/mount need root, which this machine lacks)" >&2
    exit 1
}

# --- cleanup: remove what this script created in docker ---------------------
# Runs on any exit (success, failure, or interrupt), preserving the original
# exit code. TMPD is set later (sanity check) and removed here if present.
cleanup() {
    local rc=$?
    if [[ -n "$TMPD" ]]; then
        rm -rf -- "$TMPD"
    fi
    echo ">> cleanup: removing docker image and build cache..."
    docker rmi -f "$MAKER_IMAGE" >/dev/null 2>&1 || true
    # Prunes *unused* build cache. Note: this is global, not scoped to this
    # build — other projects' unused build cache may be removed as well.
    docker builder prune -f --quiet >/dev/null 2>&1 || true
    # Also remove the base image — but only if nothing else uses it: no
    # container (running or stopped) was created from it, and no other image
    # in the store is built on top of it (i.e. shares its base layers).
    if docker image inspect "$BASE_IMAGE" >/dev/null 2>&1; then
        local in_use=0 base_id first_layer img_id img_layers
        base_id="$(docker image inspect --format '{{.Id}}' "$BASE_IMAGE" 2>/dev/null)" || base_id=""
        first_layer="$(docker image inspect --format '{{index .RootFS.Layers 0}}' "$BASE_IMAGE" 2>/dev/null)" || first_layer=""
        # 1) any container (running or stopped) created from it?
        if [[ -n "$(docker ps -a -q --filter "ancestor=$BASE_IMAGE" 2>/dev/null)" ]]; then
            in_use=1
        fi
        # 2) any other image in the store built on top of it (shares its layers)?
        if (( ! in_use )) && [[ -n "$base_id" && -n "$first_layer" ]]; then
            while IFS= read -r img_id; do
                if [[ "$img_id" == *"${base_id:7:12}"* ]]; then
                    continue  # the base image itself
                fi
                img_layers="$(docker image inspect --format '{{.RootFS.Layers}}' "$img_id" 2>/dev/null)" || img_layers=""
                if [[ "$img_layers" == *"$first_layer"* ]]; then
                    in_use=1
                    break
                fi
            done < <(docker image ls -q 2>/dev/null)
        fi
        if (( ! in_use )) && [[ -n "$base_id" && -n "$first_layer" ]]; then
            # guard: only delete when the checks above actually ran
            echo ">> cleanup: $BASE_IMAGE is not used elsewhere, removing it too..."
            docker rmi -f "$BASE_IMAGE" >/dev/null 2>&1 || true
        else
            echo ">> cleanup: $BASE_IMAGE is in use elsewhere (or could not be checked), keeping it"
        fi
    fi
    exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# --- helper image: debian + btrfs-progs + attr (removed by the EXIT trap) ---
if ! docker image inspect "$MAKER_IMAGE" >/dev/null 2>&1; then
    echo ">> building helper image $MAKER_IMAGE..."
    docker build -t "$MAKER_IMAGE" - <<EOF
FROM ${BASE_IMAGE}
RUN apt-get update \
 && apt-get install -y --no-install-recommends btrfs-progs attr \
 && apt-get clean && rm -rf /var/lib/apt/lists/*
EOF
fi

# --- fresh image file ------------------------------------------------------
rm -f -- "$OUT"
OUT_DIR="$(dirname -- "$OUT")"
OUT_NAME="$(basename -- "$OUT")"
truncate -s "$SIZE" -- "$OUT"
echo ">> created sparse image: $OUT ($SIZE)"

# --- build the filesystem in a privileged container ------------------------
# (docker run needs -i so that `bash -s` inside gets the script on stdin)
echo ">> populating btrfs filesystem (via docker)..."
docker run --rm \
    -i \
    --privileged \
    -e IMG_NAME="$OUT_NAME" \
    -e FS_UID="$FS_UID" \
    -e FS_GID="$FS_GID" \
    -v "$OUT_DIR":/work \
    "$MAKER_IMAGE" bash -s <<'CONTAINER_SCRIPT'
set -euo pipefail
cd /work
IMG="$IMG_NAME"

mkfs.btrfs -f "$IMG" >/dev/null

LOOP="$(losetup -f --show "/work/$IMG")"
cleanup() { umount /mnt 2>/dev/null || true; losetup -d "$LOOP" 2>/dev/null || true; }
trap cleanup EXIT
mount "$LOOP" /mnt

TOMB_DIR='Tomb raider па рюске!'
TARGET_FILE='А́а́, Е́е́, И́и́, О́о́, У́у́, Ы́ы́, Э́э́, Ю́ю́, Я́я́.txt'

# The file ServerTest recovers (name must match the test exactly).
mkdir -p "/mnt/$TOMB_DIR"
printf '%s\n' \
    'The quick brown fox jumps over the lazy dog.' \
    'Файл с «неудобным» именем: пробелы, знак !, диакритика.' \
    'If btrfs-restore brought you here, it works.' \
    > "/mnt/$TOMB_DIR/$TARGET_FILE"

# A few regular directories and files (~19 items in total, so the dry-run
# listing and a full restore have something realistic to show).
mkdir -p /mnt/docs/notes /mnt/docs/papers /mnt/src/utils /mnt/data/logs /mnt/music /mnt/empty-dir

echo '# Test data' > /mnt/docs/readme.md
echo 'Generated by make-test-img.sh for the btrfs-file-restorer test suite.' >> /mnt/docs/readme.md
echo 'Decisions: ship it.' > /mnt/docs/notes/meeting-2024-01-15.txt
printf 'A (fake) paper about lazy superblocks and why %s is fine.\n' 'btrfs restore' \
    > /mnt/docs/papers/lazy-superblocks.txt
ln -s readme.md /mnt/docs/link-to-readme

printf 'fun main() {\n    println("hello from the test image")\n}\n' > /mnt/src/Main.kt
printf 'fun answerToLife(): Int = 42\n' > /mnt/src/utils/helpers.kt

printf '%s\n' \
    '2024-01-15T10:00:00Z INFO  server started' \
    '2024-01-15T10:05:31Z WARN  free space low (12%)' \
    '2024-01-15T10:09:02Z ERROR btrfs: no hole in the sky' \
    > /mnt/data/logs/server.log

# A >4 KiB file so its data lives in a real extent (not inline):
# exercises the extent-copying path of btrfs restore.
head -c 96K /dev/urandom > /mnt/data/backup-2023-12-01.bin

printf '#EXTM3U\n#EXTINF:210,track one\ntrack01.mp3\n' > /mnt/music/playlist.m3u

# A user.* xattr (restorable unprivileged, unlike trusted.*/security.*).
setfattr -n user.comment -v 'generated by make-test-img.sh' /mnt/docs/readme.md

# The test restores with --metadata as an unprivileged user: every restored
# inode is chowned to its original owner, which only works if that owner is
# the user running the restore.
chown -R "$FS_UID:$FS_GID" /mnt

sync
umount /mnt
losetup -d "$LOOP"
trap - EXIT
echo "container: done"
CONTAINER_SCRIPT

# --- host-side sanity check: reproduce what ServerTest does ----------------
if ! command -v btrfs >/dev/null 2>&1; then
    echo ">> warning: 'btrfs' not found on host, skipping sanity check" >&2
    exit 0
fi
echo ">> sanity check: btrfs restore --dry-run ..."
btrfs restore -v --dry-run "$OUT" .

# Build the same --path-regex ServerTest.kt builds from TARGET_PATH
# (escape non-whitespace non-alphanumerics, then nest: (|/a(|/b))).
if command -v python3 >/dev/null 2>&1; then
    REGEX="$(TARGET_PATH="$TARGET_PATH" python3 - <<'PY'
import os, unicodedata
path = os.environ['TARGET_PATH']
parts = path.split('/')[1:]
escaped = []
for p in parts:
    out = []
    for c in p:
        # Kotlin's Char.isLetterOrDigit == true for L*/N* categories
        if not (c.isspace() or unicodedata.category(c)[0] in ('L', 'N')):
            out.append('\\')
        out.append(c)
    escaped.append(''.join(out))
acc = ''
for e in reversed(escaped):
    acc = '(|/' + e + acc + ')'
print('^' + acc + '$', end='')
PY
)"
    TMPD="$(mktemp -d)"   # removed by the cleanup trap on exit
    echo ">> sanity check: btrfs restore --path-regex (into $TMPD)..."
    if btrfs restore --metadata --xattr --symlinks --path-regex "$REGEX" "$OUT" "$TMPD"; then
        RESTORED="$TMPD/${TARGET_PATH}"
        if [[ -s "$RESTORED" ]]; then
            echo ">> OK: restored file found, $(wc -c < "$RESTORED") bytes:"
            cat "$RESTORED"
            echo ">> success: $OUT is ready for ServerTest"
        else
            echo ">> FAIL: restored file missing or empty: $RESTORED" >&2
            exit 1
        fi
    else
        echo ">> FAIL: btrfs restore --path-regex reported errors" >&2
        exit 1
    fi
fi
