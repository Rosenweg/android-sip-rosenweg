#!/bin/bash
# Holt librwtunnel_jni.so (Fernwartung, Telefonseite des adb-Tunnels) aus Rosenweg/rosenweg-vm
# und legt sie unter app/src/main/jniLibs ab, samt RwFernwartung.java und der Herkunft.
#
#   werkzeuge/rwtunnel-holen.sh <pfad/zu/rosenweg-vm> [<commit oder zweig>]
#
# Baut mit dem Android-NDK (native/rwtunnel/bauen.sh android-jni). Die .so liegt im Repo, damit
# Rosenweg Geraet ohne Go baut; geaendert wird sie nur ueber dieses Skript (RWTUNNEL.txt).
set -euo pipefail
vm="${1:?Pfad zu rosenweg-vm fehlt}"
ref="${2:-HEAD}"
hier="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'git -C "$vm" worktree remove --force "$tmp/vm" >/dev/null 2>&1 || true; rm -rf "$tmp"' EXIT
git -C "$vm" worktree add --detach "$tmp/vm" "$ref" >/dev/null
commit="$(git -C "$tmp/vm" rev-parse HEAD)"
# lokales Go von rosenweg-vm, falls vorhanden
[ -x "$vm/.werkzeuge/go/bin/go" ] && export PATH="$vm/.werkzeuge/go/bin:$PATH"
"$tmp/vm/native/rwtunnel/bauen.sh" android-jni
for abi in arm64-v8a x86_64; do
    install -d "$hier/app/src/main/jniLibs/$abi"
    install -m 0644 "$tmp/vm/native/rwtunnel/build/android-jni/$abi/librwtunnel_jni.so" "$hier/app/src/main/jniLibs/$abi/"
done
install -d "$hier/app/src/main/java/ch/rosenweg4303/rwtunnel"
install -m 0644 "$tmp/vm/native/rwtunnel/jni/java/ch/rosenweg4303/rwtunnel/RwFernwartung.java" \
    "$hier/app/src/main/java/ch/rosenweg4303/rwtunnel/RwFernwartung.java"
{
    echo "librwtunnel_jni.so aus Rosenweg/rosenweg-vm native/rwtunnel (jni/), GPL-3.0-or-later"
    echo "Commit: $commit"
    echo "Gebaut: $(date -u +%Y-%m-%dT%H:%M:%SZ) mit $(go version)"
    (cd "$hier/app/src/main/jniLibs" && shasum -a 256 */librwtunnel_jni.so)
} > "$hier/app/src/main/jniLibs/RWTUNNEL.txt"
cat "$hier/app/src/main/jniLibs/RWTUNNEL.txt"
