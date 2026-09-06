# Miracle Linux — v0.1 "GhostByte" (proof of concept)

Real Debian/Kali running inside PRoot, fully bundled into one Android app,
launched with zero manual setup — no separate Termux install required.

## Build strategy: everything heavy happens on GitHub Actions, not the tablet

Development happens in Termux + Kali PRoot on the tablet — editing code,
git commits, pushing. The actual Android/Gradle build (which needs a full
Android SDK and real compute) runs on GitHub's servers, not locally.

Workflow:
1. Edit code in Termux, `git push`
2. GitHub Actions (`.github/workflows/build.yml`) builds the APK automatically
3. Download the finished `.apk` from the workflow run's "Artifacts" section
4. Install it on the tablet to test

No Android Studio, no local Gradle, no local SDK needed at any point.

## What's still missing before this actually builds

Two real binary assets are needed that this scaffold doesn't include yet,
since they're specific to your device and too large to hand-write here:

### 1. `app/src/main/jniLibs/arm64-v8a/libproot.so`
This is the real `proot` binary, just renamed so Android's packaging system
extracts it to a real executable file path instead of leaving it compressed
inside the APK. **Don't build this from scratch** — copy the one you already
have working in Termux:

```
cp $PREFIX/bin/proot /path/to/libproot.so
```

One thing to check before it'll work standalone: Termux's `proot` may be
dynamically linked against libraries under `$PREFIX/lib` (e.g. `libtalloc`).
Run `ldd $PREFIX/bin/proot` inside Termux to see what it depends on — if
there are non-standard shared library dependencies, those need to be
bundled alongside it too (also as fake "native libs" in the same
`jniLibs/arm64-v8a/` folder), or `proot` needs to be a static build instead.

### 2. `app/src/main/assets/rootfs.tar.gz`
**Do NOT tar up your existing Kali install for this** — that's your personal
dev environment (955 packages per your `fastfetch` output), not a minimal
base. Bundling that would ship a bloated APK and contradict the "minimal by
default, users install what they want" decision. Instead, set up a
*separate*, clean, minimal rootfs specifically for bundling:

```
proot-distro install debian --override-alias miracle-base
tar -czf rootfs.tar.gz -C ~/.termux/proot-distro/installed-rootfs/miracle-base .
```

This keeps your actual dev Kali environment untouched and gives you a
genuinely minimal (~100-150MB) base to ship — real Debian `apt`, real
`bash`, nothing extra pre-installed. Wireshark, Metasploit, wordlists, etc.
are the user's call, installed after the fact, at their own resource cost.

Both files are too large for normal git history. Recommended: attach them
as assets on a GitHub Release in this repo once, then uncomment and fill in
the download step already stubbed out in `build.yml`.

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
