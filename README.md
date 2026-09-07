All development can happen from Termux + Kali PRoot directly on an Android
device — editing code, git commits, pushing. The actual Android/Gradle
build (which needs a full Android SDK and real compute) runs on GitHub's
servers instead of locally, so no Android Studio, local Gradle, or local
SDK install is ever required.

Workflow:
1. Edit code, `git push`
2. GitHub Actions (`.github/workflows/build.yml`) builds the APK automatically
3. Download the finished `.apk` from the workflow run's "Artifacts" section
4. Install it on a device to test

## Preparing the two bundled assets

Two binary assets have to be prepared once and attached to a GitHub Release
in this repo (they're too large for normal git history) — `build.yml` has
a commented-out download step ready to be filled in once they're uploaded.

### 1. `app/src/main/jniLibs/arm64-v8a/libproot.so`
The real `proot` binary, renamed so Android's packaging system extracts it
to a real executable file path instead of leaving it compressed inside the
APK:

```
cp $PREFIX/bin/proot libproot.so
```

`proot` typically depends on two Termux-specific shared libraries beyond
Android's own system libs — check with `ldd $PREFIX/bin/proot`. If present,
copy both alongside it, with `libtalloc.so.2` renamed to `libtalloc.so` for
packaging (Android's build system only reliably packages plain `.so`
filenames):

```
cp $PREFIX/lib/libtalloc.so.2 libtalloc.so
cp $PREFIX/lib/libandroid-shmem.so libandroid-shmem.so
```

`MainActivity.kt` restores `libtalloc.so`'s original name (`libtalloc.so.2`)
at runtime in a private directory and points `LD_LIBRARY_PATH` there before
launching `proot` — this is needed because Termux's `proot` build has
Termux's own absolute data path baked in for finding these libraries, which
won't exist inside a different app's sandbox.

### 2. `app/src/main/assets/rootfs.tar.gz`
A minimal, purpose-built Debian rootfs — not a personal, heavily-customized
dev environment, which would ship a bloated APK and contradict the
"minimal by default, users install what they want" design:

```
proot-distro install debian --override-alias miracle-base
tar -czf rootfs.tar.gz -C "$(find $PREFIX/var/lib/proot-distro -type d -name miracle-base)/rootfs" .
```

This gives a genuinely minimal (~150MB extracted, well under GitHub's 2GB
release-asset limit) base — real Debian `apt`, real `bash`, nothing extra
pre-installed. Heavier tools (Wireshark, Metasploit, wordlists, etc.) are
each user's own call, installed after the fact via real `apt`, at their own
resource cost.

**If your terminal tooling and your project files live in separate apps**
(for example, native Termux for binaries like `proot`, and a separate
proot-based distro for the actual repo), bridge files between them through
shared storage (commonly `/sdcard/Download`) rather than trying to access
one app's private data directory from the other — they're sandboxed from
each other by Android regardless of both being terminal environments.

## Known limitation in this milestone (by design, not a bug)

`MainActivity.kt` currently pipes bash's stdin/stdout through plain process
streams — not a real pseudo-terminal (pty). This is enough to prove the
core pipeline (real Debian bash, real output) but won't behave like a full
interactive terminal yet: no colors, no line-editing, no ctrl+c, no job
control. Real pty support needs native (JNI) code — the same problem
Termux solves with its own `TerminalSession` native layer — and is the
natural next milestone once this one is proven working end to end.

## The one test that matters for this milestone

Open the app → real `bash` prompt output appears → type `ls` → real Debian
filesystem output comes back. That's it. Everything else (desktop UI,
theming, bridge commands) builds on top of this once it's proven solid.
EOF
echo done





