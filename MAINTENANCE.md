# Alchemy maintenance plan

Status: **planning / pre-feature**
Scope: restore a reproducible, headless-verifiable build and stabilise the repo so
feature work can resume. No feature or behaviour changes land on this branch.

Branch: `chore/revive-build-and-ci` (from `master` @ `1a4ac99`)

## Working agreements

- Push only after the maintainer confirms. Local commits proceed without asking;
  `git push` (including `--force-with-lease`) and `gh release` do not.
- `origin` is the maintainer's fork (`pbiswal/Alchemy`). `upstream` (karldd) is
  fetch-only and its `pushurl` is disabled, so an upstream push cannot happen by
  accident.

---

## Baseline (verified 2026, OpenJDK 25.0.4.1, Fedora 44 x86_64)

Verified facts this plan is built on:

- All 91 source files (~25.6k LOC) **compile clean on JDK 25** with only
  deprecation/unchecked notes:

  ```sh
  CP=$(find lib -name '*.jar' | grep -v ant/ | tr '\n' ':')
  find src -name '*.java' > /tmp/srcs.txt
  javac -encoding ISO-8859-1 -cp "$CP" -d /tmp/alcbuild @/tmp/srcs.txt   # 0 errors
  ```

- The shipped `releases/Alchemy-008.tar.gz` **still runs on JDK 25**: 25 JPF plugins
  register and start, canvas renders (checked under Xvfb). Runtime is healthy.
- `nbproject/project.properties` sets `javac.source/target=1.5`, which modern `javac`
  rejects outright: `error: Source option 5 is no longer supported. Use 8 or later.`
- A plain `javac` build cannot run the app: module archives (`modules/*.zip`) are only
  produced by the NetBeans/Ant pipeline, so launch aborts with
  *"At least one module must be installed for Alchemy to run."*
- No tests, no CI, no `.gitignore`. `releases/` = 182 MB of committed binaries,
  `.git` = 204 MB.

Conclusion: **the source is fine, the build is dead.** Workstream 1 is the blocker for
everything else.

---

## Workstream 1 - build + CI (blocking)

Goal: `git clone && <one command>` produces a runnable Alchemy with loadable modules,
and CI proves it on every push.

- [x] Toolchain: **Gradle** (wrapper committed) - chosen over a standalone Ant script, so
      no `nbproject/build-impl.xml` import, `ant-contrib` or `jarbundler` is needed.
- [x] Raise `source`/`target` from `1.5` to `17` (`--release 17`). All 91 files compile
      with only deprecation/unchecked notes; no intermediate `8` step was needed.
- [x] Replace the custom `jpf-zip` Ant task with a Gradle packaging step that reads each
      `modules/*/plugin.xml` and emits `build/modules/<id>-<version>.zip` (28 archives).
- [x] `dist` task stages `modules/`, the app jar and `lib/jpen` natives together, and `run`
      / `smokeTest` set `workingDir` to that layout - plugin discovery uses the
      cwd-relative `new File("modules")` in `AlcPlugins`.
- [ ] Move replaceable deps to Maven Central: Batik, MigLayout, commons-logging,
      XMLCommons/`xml-apis`. Keep vendored: JPF 1.5.1, JPen (+ natives), Foxtrot.
      Quaqua leaves with the macOS work in Workstream 3, not with this list.
- [ ] Evaluate `lib/itext.jar` (unlabelled vintage) against OpenPDF before touching
      PDF export; PDF export is a user-visible feature, do not change behaviour here.
- [ ] Delete the developer-local leftover in `nbproject/project.properties`:
      `file.reference.foxtrot.jar-1=../../../../Sony/Exhibition Workshop/...`
- [x] `run` and `smokeTest` tasks exist. `smokeTest` boots the real app under a private
      X display and asserts: no fatal start-up error, window/canvas sized, menu bar and
      tool bar populated (48 menu items, 18 tool buttons), 28 archives -> 27 plugins
      (16 create + 11 affect), and no unexpected exception trace on stdout/stderr.
- [x] GitHub Actions matrix: ubuntu / macos / windows x JDK 17 / 21 / 25
      (`.github/workflows/ci.yml`), one `build` job running
      `build distArchive checkPackage` everywhere and `verifyDist` on Linux under Xvfb,
      so a packaging or launcher regression is caught on the commit that caused it and
      the archive is proven to start on the platform that gets published. macOS smoke is
      `continue-on-error` - see the macOS note below; macOS compile/package stays a real
      gate. Results are mirrored into the job summary with a `::error::` annotation, and
      `smoke-result.txt`, `smoke-stdout.log` and the app log are uploaded on failure,
      because Actions job logs need a token to read and fork CI runs get debugged from
      outside. No deploy step - publishing lives in `release.yml`.
- [ ] Keep NetBeans project files working (or state explicitly in README that they are
      retired) - do not leave a half-supported second build path.

**Build verification gotcha:** this checkout runs with `core.fileMode=false`, so the
wrapper was committed mode 644 and a clone could not execute it. Building in place never
shows that - only building a fresh clone does, so the acceptance command is:

```sh
git clone -b chore/revive-build-and-ci <fork> && cd Alchemy
xvfb-run -a ./gradlew build smokeTest     # no system Gradle required
```

Related: `clean` cannot delete `build/` while `./gradlew run` is alive, because the app's
cwd is `build/dist` and the mount turns open-but-deleted files into `.fuse_hidden*`.

**Rule for any future UI test:** never grab the screen (`Robot.createScreenCapture`,
`import -window root`, etc.). A grab reads whatever display the process is on, so it can
"pass" against an unrelated desktop, and on a desktop session with a screenshots portal it
prompts the user for remote-desktop consent. `SmokeTest` refuses to run on display `:0`/`:1`
for this reason; assert on component/structure state, or render the drawing model off-screen.
The refusal is checked only where `DISPLAY` means something (X11): macOS and Windows have no
`DISPLAY`, and CI found out the hard way that requiring one there rejects a valid runner.

**Acceptance:** a clean clone builds and boots with `./gradlew build smokeTest` on a machine
with only a JDK installed (verified: clean build -> 28 module archives -> smoke PASS, 0
exception traces); CI green on Linux/macOS/Windows.

### Known limitations and follow-ups

- **The harness needs its own tests, and now has a start** - `./gradlew harnessTest`
  (`harness/java/org/alchemy/harness/HarnessTest.java`, part of `check`, no test
  framework and no display, so it runs anywhere the build runs). Two defects it pins,
  both found by feeding the parser a known-bad log. First, the stdout and stderr tees
  shared one capture buffer and each rewrote it with only its own stream's bytes, so
  whichever stream printed last erased the other: an exception thrown to stderr during
  start-up disappeared as soon as the app logged to stdout, and the smoke test reported
  `exception_traces=0` for a run that had in fact thrown. Second, a trace header was
  recognised only when the throwable type was package-qualified, which excluded
  `java.lang.Exception` itself - a bare `Exception` on stderr was silently allowed.
  The parser is deliberately not a general log analyser: a throwable named
  `...Exception`/`Error`/`Throwable` always counts as a trace, a bare
  `Exception`/`Error`/`Throwable` only when a stack frame follows, so ordinary log
  prose is not mistaken for one. Before trusting a harness change, prove it fails on an
  injected trace; a change that only ever sees clean runs proves nothing.
- **No pixel checks yet, and none by screen capture** - `AlcCanvas` is not exercised at
  the pixel level in CI. When that arrives, render the drawing model into a
  `BufferedImage` inside the harness JVM and compare that; grabbing the display is ruled
  out above, and font rendering varies enough across runner images that pixel comparison
  without a pinned font setup flakes.
- **macOS compiles and packages, but its runtime is known-red** - three separate causes,
  all pre-existing and all now named in the CI annotations:
  1. `ch.randelshofer.quaqua.util.BinaryPListParser: parseInt: unsupported byte count:16`
     - Quaqua 3.x (vendored 2011) reads a modern macOS plist with an int-sized buffer.
  2. `IllegalAccessException` - `OSXAdapter` reflectively calls `com.apple.eawt.Application`,
     which the module system no longer exports; the replacement is `java.awt.desktop`
     (JDK 9+).
  3. `NullPointerException` in `AlcWindow.resizeWindow` - a cascade of (2): the failed
     adapter leaves `Alchemy.toolBar` null at the first resize.

  (1) argues for dropping Quaqua for the platform Aqua look-and-feel, (3) needs a null
  guard regardless. This needs a Mac to iterate on, so it stays out of Workstream 1;
  Linux and Windows boot clean. Until it lands, the macOS smoke step is informational so
  the diagnosis is published on every push without blocking unrelated work - flip
  `continue-on-error` back when it is fixed.

---

## Workstream 1b - ship something people can run

Goal: `./gradlew distArchive` produces a package with the layout the application actually
expects, and pushing a tag publishes it. The last downloadable Alchemy was built in 2018
against a JDK that no longer exists.

- [x] `dist` stages the 008 layout: `alchemy.jar` + `lib/` (22 dependency jars) +
      `lib/jpen/` (per-OS JNI libraries) + `modules/` (28 archives) + `Alchemy` /
      `Alchemy.bat` + `LICENSE.md` + `README.md` + a stamped `NOTICE.txt`.
- [x] `alchemy.jar` carries a `Class-Path` manifest, so `java -jar alchemy.jar` works
      from any directory - relative to the jar, not the cwd, which is why `run`,
      `smokeTest` and the launchers all set their working directory to the dist layout.
- [x] `distArchive` writes `.tar.gz`, `.zip` and `SHA256SUMS.txt`.
- [x] `checkPackage` asserts the unpacked tree is launchable in principle: `Main-Class`
      present, every `Class-Path` entry exists, 28 module archives, `./Alchemy` present
      and executable.
- [x] `verifyDist` launches the unpacked package **through its own launcher** and waits
      for `active/total: 28 of 28`, alive and exception-free.
- [x] `.github/workflows/release.yml`: pushing `vA.B.C` builds one portable package,
      launch-tests it, checks the same recipe on Windows and macOS, and publishes a
      GitHub Release with both archive formats plus `SHA256SUMS.txt`.
- [x] `v0.9.0` cut and published: https://github.com/pbiswal/Alchemy/releases/tag/v0.9.0
- [x] The every-push CI gate runs the packaging too (`distArchive`, `checkPackage`, and
      `verifyDist` on Linux), so a packaging regression is red on the commit that caused
      it instead of at release time. Proven by sabotage: a `NOTICE.txt` with an
      unsubstituted `@COMMIT@` fails the gate as intended.
- [x] Archive entries carry the commit date and the launcher keeps its exec bit in both
      formats; `checkPackage` asserts the dates, the mode and the gzip header.
- [ ] Windows release gating: the release workflow launch-tests the package on Windows
      informationally (`verify-recipe`), because nobody has yet seen `Alchemy.bat` start
      inside a Windows runner. When that step reports `result=PASS` on a real tag, make
      `verifyDist` a normal gating step there instead of the tolerated one.

**The two harnesses answer different questions, and both are needed.** `smokeTest` runs
the classes off Gradle's own classpath, so it can pass while `java -jar` cannot start;
`verifyDist` runs the bytes out of the archive through the shipped launcher, so it is the
only check that can see a missing module directory, a jar name the manifest does not
match, or a launcher that forgot `java.library.path`. They share one exception allowlist
(`SmokeTest.ALLOWED_EXCEPTIONS`) so a trace cannot be forgiven in one and failed in the
other.

**Entry dates: why the archives are stamped rather than merely sorted**

`reproducibleFileOrder` removes ordering, but Gradle still wrote the moment of building
into every entry and, with no source files in the archive, that fell back to the epoch: every
one of the 69 entries in an unpacked Alchemy folder built in November 2026 was dated
1970-01-02 (tar) or 1980-02-01 (zip). That is worse than a moving timestamp: `tar -tvf` and
`jar -vft` report 1970 for files that did not exist in 1970, and anyone comparing an old
download with a new one by mtime would conclude the new one is fifty-six years older.

Gradle 9.7.0 added the property this needed: `AbstractArchiveTask.reproducibleFileTimestamp`, a
`Property<Long>` of epoch milliseconds that archive tasks consult when `preserveFileTimestamps` is
false (Elastic filed gradle/gradle#36175 asking for it for exactly this use — stamping with a git
commit time). `build.gradle` therefore says, once per release archive:

```groovy
reproducibleFileTimestamp = commitEntryTime   // providers.provider { commit time of HEAD, in ms }
```

- The date is the **commit** instant (`git log -1 --format=%ct`) — the only fact about a
  release that is both fixed and true. The source date rather than the build date is what
  makes two rebuilds comparable.
- `build.gradle` refuses to run under Gradle older than 9.7.0 rather than letting it die with
  "unknown property"; the wrapper pins the version, so the only way to hit the message is to
  run a hand-installed Gradle.
- **There used to be a byte-level patcher here, and deleting it was the point.** Until now this
  file's script plugin rewrote the mtime of every entry in the finished archive, and the readers
  in the same file then checked that the entries said what the writer had written. A writer and a
  reader that misunderstand a header agree with each other, and the check that exists to catch
  the other passes — the worst property an assertion can have. Before removing anything, the two
  mechanisms were measured against each other from the same commit with the same tree state:
  identical SHA-256 for both formats (`02cf8a49174de6b9…` tar, `22cc806701242feb…` zip) — so v0.9.0
  did not need republishing, and the patcher went. The readers stayed, and now they audit
  somebody else's implementation, which is the only way one of them can fail usefully.
- Zip stores the date twice, in the central directory and in each local header, and DOS dates
  carry no time zone. Gradle encodes that value **in UTC itself**, so no build-time pin is needed:
  with the `systemProp.user.timezone` pin deleted, the native build was byte-identical under
  `TZ=UTC`, `TZ=America/Los_Angeles`, `TZ=Asia/Kolkata` and `TZ=America/Sao_Paulo`, and identical to
  the byte-patcher's `TZ=UTC` output as well. The pin is gone from `gradle.properties`, because a
  setting whose stated reason turns out to be false is a trap for the next reader.
- Tar needs no format setting: Gradle writes plain ustar headers here, and that is *verified* rather
  than assumed. The reader refuses an archive whose entries carry a PAX `x`/`g` header, because a PAX
  extension carries `mtime=` and extractors honour that instead of the field being asserted — the check
  would pass or fail about a field nothing reads. (Gradle 9.8 exposes no `Tar.format` to set anyway.)

`checkPackage` asserts the result on both formats — a stamp nobody checks is a stamp that silently
stops being the commit: every tar entry equals the commit epoch, every zip entry carries the commit
year. Zip is asserted on the date alone because the format drops the time and the zone; asserting the
instant would claim a precision the bytes do not have.

**Sabotage the stamp on purpose when you touch it.** In `build.gradle`, change
`seconds == null ? null : seconds * 1000L` in the `commitEntryTime` provider to drop the
milliseconds — the classic unit slip — and run `:checkPackage`. It must fail on both halves:

```
alchemy-0.9.0-unofficial.tar.gz: tar entry times are not the commit time - expected [1793656258], found [1793656]
alchemy-0.9.0-unofficial.zip: zip entry years are not the commit year - expected [2026], found [1980]
```

Tar catches the truncation and zip catches the epoch fallback; either alone would let a plausible-looking
archive out. Restore with the copy you made *before* editing:

```bash
cp build.gradle /tmp/build.gradle.GOOD   # first, always
# sabotage, run :checkPackage, expect failure
cp /tmp/build.gradle.GOOD build.gradle
```

Do not restore with `git checkout -- build.gradle` while the work you are testing is uncommitted. It
silently discards every uncommitted line in that file — including the parts that were not sabotaged —
and says nothing while doing it. Nothing in the sabotaged region looks wrong afterwards; you only
notice that the feature you spent the day on has stopped printing.

**Byte-level sabotage of a built archive is a false test.** Patching the mtime or a mode field inside
`build/distributions/*.tar.gz` and re-running `:checkPackage` *passes*, because `distTar` sees that its
output changed and rebuilds — restamping what you tampered with — before the check ever reads it. So
producers are sabotaged by editing the producer (the unit slip, above), and *detectors* are validated
against synthetic archives of known bytes: a ustar/gzip fixture written in Python with the launcher at
`0644`, a jar at `0666`, an `FNAME`, or a non-zero wrapper mtime, fed to the readers from a scratch
project whose whole build script is `apply from: '…/gradle/archive-checks.gradle'`. Every failure mode
above was caught that way, and the well-formed fixture passes, which is the half a sabotage alone never
proves.

**The checkers refuse what they cannot read.** A tar whose entries carry PAX extension headers is
rejected rather than read, because PAX mtime overrides the ustar field the reader looks at. So is a
zip carrying a zip64 end-of-central-directory, on either tell: the 20-byte locator before the EOCD
record, or the 0xffffffff / 0xffff sentinels a zip64 writes into the plain record's offset and count
fields. Walking such a file with 32-bit assumptions would wander off to a nonsense offset and come
back reporting an empty package. A zip whose entries merely carry zip64 *extra fields* while the EOCD
stays honest has real 32-bit fields and is read normally — `z64_extra.zip` and `z64_eocd.zip` are
fixtures for exactly that pair, first rejected and second accepted. Running them is what found the
locator constant to be wrong: the constant I wrote was 0x07064b50, which is the zip64 EOCD **record**
(`PK\x06\x07`), while the **locator** that sits immediately before the EOCD — the thing this reader
actually looks for — is `PK\x06\x08`, little-endian 0x08064b50. Reading a spec summary is not the same
as asserting both fixtures.

**Modes: asserted from the archive bytes, not from the filesystem.** `checkPackage` also runs
`entryModeProblems(tar, zip, "${distName}/Alchemy")` and `gzipProblems(tar)` from
`gradle/archive-checks.gradle`, which read the mode fields exactly the way the date code reads the
time fields — by walking the finished archives. The extracted tree only ever proves that *this*
machine's archiver and filesystem agreed; `unpackDist` could drop the mode on every platform and
`canExecute()` would still pass wherever the tar step is skipped. The contract is one executable
path per archive (the launcher, matched by its full in-archive path, because every entry is prefixed
by the distribution directory), everything else `0644`, and the two formats agreeing entry by entry.

Three byte-layout facts decide whether such a reader works at all:

- Gradle writes the tar mode as a **full mode word** — `0100644`, not `0644` — so the comparison
  masks with `& 07777`, and the field's trailing NUL has to be stripped before base-8 parsing.
- Zip entries declare **MS-DOS as the host** yet carry real unix modes in the external attributes.
  Gate on the unix file-type bits (`0100000` file, `0040000` directory), never on the host byte,
  or the reader sees no modes and reports a clean pass over nothing.
- If a future Gradle stops writing unix modes, every mode reads as absent and the check prints a
  note instead of asserting. "Not checked, and saying so" beats "checked, and silently vacuous".

The gzip wrapper gets its own assertion, because it is where a build machine leaks: magic, method 8,
no `FNAME`, no `FCOMMENT`, no `FHCRC`, and an mtime of 0. The mtime has teeth — a non-zero wrapper
mtime is the moment of building written back into a release whose whole point is that it carries the
commit instant instead. Gradle writes FLG `0x00` and mtime `0` today; the assertion is what stops
"happens to" from becoming "no longer".

Both readers only ever read, which was verified rather than assumed: with them in place the built tar
is byte-identical to the published `v0.9.0` archive member for member except `NOTICE.txt`, which
differs only because the working tree was dirty at the time.

**The configuration cache is off: what it would buy, and why that is not enough**

`org.gradle.configuration-cache` stays disabled, and the case for leaving it off is now measured
instead of asserted. The build cache is what helps here at all: `clean checkPackage` is **3s with
`org.gradle.caching` on and 4s with `--no-build-cache`**, and the full gated pipeline (`clean check
build distArchive checkPackage verifyDist`, `--no-daemon`, JDK 25, harness and smoke test actually
executing, under `xvfb-run`) is **4s either way**. Numbers in an earlier draft of this file - 7.3s for
that pipeline, and "0.05s" for the cache - were taken in states that were not comparable (a cold build
cache; a wrapper whose own exit path was being timed), and the ones above were taken back-to-back on
this tree. The same back-to-back runs settle the cost of the stamping rewrite too: the byte-patcher at
`HEAD` and the Gradle property in the working tree both finish the gated pipeline in 4s.

It also still does not work, and what fails has changed, which is worth recording:

```
> Task :distTar
> External process execution requirement
  - Problem 1/2: external process started 'git log -1 --format=%ct'
    at build.gradle: line 266   (gitOutput, called from commitStamp)
  2 problems, 1 unique problem, reported for Build file 'build.gradle'.
```

The script-plugin serialisation failures this section used to describe (`Cannot invoke method
tarTimes() on null object`) are gone: the closures that caused them were deleted with the patcher.
What remains is the commit stamp, and the lesson is subtler. `commitEntryTime` *is* a `Provider`, so
nothing reads it at configuration time in the ordinary build; but a provider that feeds a **task
input** is queried when Gradle stores the cache entry, and storing is configuration-time work. The
`git` process therefore still starts during capture, and capture refuses it. Verified failing in every
state of `.gradle/` (warm, and after `rm -rf`), in ~3.3s, where the same graph without the flag
succeeds in 3.0s.

The supported shape is a `ValueSource`, whose entire purpose is "an external value, computed when the
task runs, tracked as an input". `ValueSource` implementations are Java classes and a script plugin
cannot declare classes, so it means `buildSrc` - plus the `JavaExec` forks in `run`, `smokeTest` and
`verifyDist` being straightened out, since the cache rejects a task action that forks a process too.
That is machinery bought for a saving the measurements above put at zero, in a project whose standing
risk is having too much machinery for 25k lines. The day configuration time becomes worth saving,
`ValueSource` is the way, and this paragraph is the reason it is not done yet.

Two lessons from the attempt outlive it:

- **A build only ever run with a warm `.gradle/` has not been tested.** When build logic changes, run
  it once with that directory removed: that is the checkout CI gets. The first round of
  configuration-cache numbers looked encouraging because a warm `.gradle/` was silently *reusing* a
  stored entry, so every "run" was a reuse and the comparison was between reuse and reuse.
- **`gradle.properties` is build behaviour.** It was untracked while these settings were being tuned,
  which meant every local build ran in a mode no CI job executed. It is committed now, with the
  measurements written into it. A related trap, hit while correcting this section: a command line in
  the file carried `-PenableCC=true`, a property this build never reads, so the timings labelled
  "cache ON" beside it had been measured with the cache off.

What the attempt left behind is worth keeping even without the cache: task actions now take plain
`String`/`File` snapshots (`repoDir`, `versionString`) and `Provider`s (`distTar`/`distZip`
`archiveFile`) instead of resolving `rootDir`, `project`, `file()` or *other task objects* at execution
time. Those were real configuration-cache violations, and reading configuration-phase objects in the
execution phase is a bug waiting for the first execution-time oddity — in a build script, the same
class of lie that a `catch (Exception)` hides in the product.

**Packaging decisions, and why**

| Decision | Rationale |
| --- | --- |
| Thin jar plus `lib/`, no fat jar | The dependencies are GPL or otherwise encumbered binary jars. Keeping them separate keeps per-jar licence provenance and matches what 008 shipped. |
| Jar named `alchemy.jar`, unversioned | The launcher, the manifest and `java -jar` all agree on one name, so re-packing a new version never requires editing a script. |
| One package, not three | `lib/jpen` carries a native library for every OS, so a per-platform archive is the same bytes under a different filename - `SHA256SUMS.txt` proved it for all three published names. One archive name cannot lie about being platform-specific; the three runners instead verify the packaging recipe and print their own checksums so a non-reproducible build is visible. |
| Archives list what may ship (`releaseIncludes`) | `build/dist` doubles as the working directory of `run`/`smokeTest` and accumulates `log/` and result files. An allowlist means nothing leaks into a release by being forgotten. |
| `NOTICE.txt` generated, never hand-written | It records the commit and version the package was built from. A hand-edited provenance file is wrong within one release. |
| macOS packaged, not launched | Its runtime is known-red (see Workstream 1). Shipping a package that is structurally checked is honest as long as the release notes and `README.md` say the runtime is broken. |

**If per-platform packages are ever wanted, differentiate first.** Splitting
`lib/jpen` by OS (keep `.so` for Linux, `.dll` for Windows, `.jnilib`/`.dylib` for
macOS) is the only change that makes the filenames mean something; it costs a wrong
filter silently losing tablet support on that platform, which today's single archive
cannot do. Re-add a classifier to `build.gradle` only together with such a filter and a
`checkPackage` assertion that the archive holds the natives for exactly one OS.

**Cutting a release**

```sh
./gradlew clean build distArchive                 # local package + checksums
xvfb-run -a ./gradlew verifyDist                  # launch the unpacked package
./gradlew distArchive -PalchemyVersion=1.0.0      # override the version

git tag -s v0.9.0 -m 'Alchemy 0.9.0 (unofficial)'
git push origin v0.9.0                            # .github/workflows/release.yml publishes
```

Do not attach artifacts from a laptop: a tag-triggered build records which commit and
toolchain produced the bytes, and a hand-pushed artifact silently breaks that chain.

**Release workflow (`.github/workflows/release.yml`)**

Runs on tags matching `vA.B.C` and nowhere else, so an ordinary push can never publish.

- One `package` job (Linux): build, run every check including the launch test, upload the
  two archives and their checksums as one artifact.
- A `verify-recipe` job (Windows, macOS): builds and structurally checks the same recipe
  and prints its own checksums to the job summary. Because Gradle writes archive entries
  with fixed timestamps, those checksums are expected to match the published ones
  exactly - the first release produced identical bytes on all three runners, which is
  evidence the packaging is portable and reproducible. Windows additionally tries
  launching the package there, informationally.
- A `release` job publishes with auto-generated notes and nothing else. If the package
  job fails, no release is created, so a failed release can never point at half a build.

Four release-specific rules:

- **The notes are generated, never hand-written.** They must keep telling the truth about
  the platform that starts and the platform that is merely packaged - that is the
  difference between an honest unofficial build and a misleading one. The first published
  notes claimed one archive per platform when the archives were byte-identical; that is
  why the workflow now publishes a single package.
- **The artifact round-trip is verified** (`sha256sum -c`) after download, because the
  published bytes are the ones that survive upload, not the ones the build printed.
- **Two audiences get two documents.** The release body is for somebody who wants to draw:
  what the program is, which file to download, how to start it, what to expect when it fails.
  It names no Gradle task, no CI job, no exception trace. Provenance, toolchain, the per-OS
  verification matrix, known defects and build commands go in `RELEASE_NOTES.md`, attached
  as an asset. A single body written for both serves neither: the person installing Alchemy
  does not want to read about `continue-on-error`, and the person auditing the build should
  not have to hunt through end-user prose to find it.
- **Backticks in generated markdown, both directions.** Inside a single-quoted shell string a
  backtick is already literal, so `\`` emits a backslash. Inside a double-quoted string a bare
  backtick begins a command substitution, so it must be escaped. Both mistakes render text
  that looks *nearly* right, which is why the generated notes are read as rendered markdown
  before shipping rather than eyeballed in a YAML diff - the first draft had the first
  mistake in sixteen lines and the second in two.

Do not upload hand-built artifacts from a laptop: a tag-triggered build records which
commit and toolchain produced the bytes, and a hand-pushed artifact silently breaks
that chain.

---

## Harness tests (`harness/java/`)

Three programs, deliberately different:

| Program | Gradle task | Needs a display | What it proves |
| --- | --- | --- | --- |
| `HarnessTest` | `harnessTest` (inside `check`) | no | the log parser the other two depend on |
| `SmokeTest` | `smokeTest` | yes (Xvfb in CI) | the app boots and activates all 28 modules |
| `ArtifactSmokeTest` | `verifyDist` | yes | the published package boots through its own launcher |

**`HarnessTest` is the one that keeps the other two honest.** `SmokeTest`'s only real
gate is "zero unexpected exception traces in the captured log", so a parser bug makes the
whole suite vacuously green. It runs as a plain `JavaExec` - no test framework, no
dependency download, no AWT - so `./gradlew check` works offline and headless. Run it
alone with `./gradlew harnessTest`; its nine checks are named after the behaviour they
pin.

**Both harness bugs below were found by sabotage** (introducing the fault, watching the
gate stay green), not by reading the code. When a change depends on a check, break it on
purpose once and confirm the check says why.

1. `LogTee` kept stdout and stderr in one buffer, so a trace on stderr was erased by the
   next line on stdout. `SmokeTest` passed while a failure it was supposed to catch sat
   unnoticed - the silent-green case.
2. The release workflow's provenance stamp used Gradle `exec` at configuration time. It
   threw, the `try/catch` swallowed it, and every package shipped `commit unknown`.
   `checkPackage` now fails if the `@...@` tokens survive, which is the assertion that
   would have caught it originally.

**Pixel checks** (`Robot.createScreenCapture`, `import -window root`) stay forbidden, and
`-Djava.awt.headless=true` is not a sufficient guard because it does not stop `Robot`.
When one is eventually added it must render to a `BufferedImage` or a `TAG_XYSURFACE`
capture session, and must be able to say which window it captured. See **Working
agreements**.

---

## Workstream 2 - module SDK + first tests

Goal: writing and verifying a module does not require NetBeans, a 4.6 MB template zip
or a human staring at a window.

- [ ] Publish the module API from source (`src/org/alchemy/core`) instead of trusting the
      committed, stale `javadocs/` HTML tree; decide whether `javadocs/` stays in git.
- [ ] Turn `templates/AlchemyModuleTemplate-008.zip` into a buildable module archetype
      that inherits Workstream 1's build.
- [ ] Document the module contract (lifecycle `setup/cleared/reselect/deselect/commited`,
      input callbacks) in `modules/README.md` with a "build and run in 3 commands" section.
- [ ] Add a headless test harness: drive `AlcCanvas` with synthetic mouse/pen events and
      assert on shape counts and on the drawing model rendered off-screen (the canvas
      paints to its own back buffer while showing, so pixel checks must render the model,
      not photograph the window).
- [ ] Add regression tests for the known-bad paths recorded as TODOs:
      `affect/ShapeSwap` ("General Path is not Serialised"), `affect/Blindness`
      ("autoreveal bug"), `create/PullShapes` ("Bug test this"),
      `core/AlcMenuBar` (vector clipboard copy unimplemented),
      `core/AlcCanvas` (scaleable image export unimplemented).
- [ ] Inventory the `static` state on `AlcModule` (`canvas`, `toolBar`, `math`,
      `window`, `colorSelector`, `bundle`) and record in writing what a module may and
      may not touch. Do not refactor it in this branch.

**Acceptance:** `./gradlew test` runs a module end-to-end with no display and no clicks.

---

## Workstream 3 - repo and platform hygiene

Goal: cheap, low-risk cleanups. Split into independently reviewable commits; the history
rewrite is a separate, announced branch because it changes every SHA.

- [ ] `chore/drop-binaries-from-history` (**separate branch, needs sign-off**): move
      `releases/` to GitHub Releases and `git filter-repo` the history (204 MB -> ~20 MB).
      Coordinate with anyone holding forks; force-push required.
- [ ] Add `.gitignore` (`build/`, `dist/`, `nbproject/private/`, `*.class`) and
      `.gitattributes` (`* text=auto eol=lf`, `*.jar binary`).
- [ ] Normalise source to UTF-8 + LF and fix mojibake: the macOS key hints in
      `core/Alchemy.java` are literal `"?"` where `Command`/`Shift`/`Option` glyphs
      belong (`KEY_MODIFIER_STRING`, `KEY_SHIFT_STRING`, `KEY_ALT_STRING`). Also drop
      `source.encoding=ISO-8859-1`; audit the 4 files with high bytes
      (`AlcColorSelector`, `AlcToolBar`, `OSXAdapter`, `create/SplatterShapes`).
- [ ] Fix Linux file open in `core/AlcUtil`: `gnome-open` / `kde-open` no longer exist;
      use `java.awt.Desktop` or `xdg-open`, and drain/`waitFor` the spawned process.
- [ ] Remove or re-source `core/AlcNative` (`System.loadLibrary("AlcNative")` for a
      binary and source that are not in this repo); its only callers are the macOS
      menu-bar toggles in `core/AlcWindow`.
- [ ] Replace the macOS platform layer rather than patching it (decided: keep macOS, drop
      Quaqua). Three steps, in this order, each verified on a Mac or a macOS runner:
      1. delete `lib/quaqua.jar` from the build and the distribution and fall back to the
         system Aqua look and feel - this removes the `BinaryPListParser.parseInt` crash
         and the six-year-old jar that causes it;
      2. replace `core/OSXAdapter`'s `com.apple.eawt` reflection with `java.awt.desktop`
         (JDK 14+), which also clears the illegal-access `IllegalAccessException`;
      3. re-check `AlcWindow.resizeWindow` - its null-toolbar NPE was a symptom of steps
         1-2 leaving the window half-initialised on macOS.
      When all three are done, remove `continue-on-error: true` from the CI smoke step and
      restore macOS as a real gate, including `verifyDist` there.
- [ ] Retire the remaining dead macOS paths: the `com.apple.eawt` /
      `com.apple.eio.FileManager` reflection does nothing on modern macOS.
- [ ] Replace fire-and-forget error handling (43 `printStackTrace`, 41 `catch (Exception)`)
      with logged, user-visible failures; remove `System.exit(0)` from plugin loading in
      favour of a real startup error path. Start with the startup/plugin/canvas paths only.
- [ ] Update README: correct the "requires JRE 1.5 or above" claim, state the JDK actually
      supported, and describe the new build commands.

**Acceptance:** Linux file-open works on a current desktop; no mojibake in any UI string;
startup failures report what went wrong.

---

## Explicitly out of scope for this branch

- Undo/select/edit or any canvas behaviour change.
- Refactoring the large core classes (`AlcCanvas` 2000, `AlcToolBar` 1592,
  `AlcColourIO` 1216, `AlcPreferences` 1135, `AlcUtil` 1099, `AlcMenuBar` 984 LOC).
- Migrating off Swing/Java2D or off JPF.
- New modules or new export formats.

## Open questions - answered

Recorded so the next session does not re-open them.

1. **Personal fork for one contributor; no upstream coordination.** Work stays on
   `pbiswal/Alchemy`; `upstream` is a fetch remote only. Nothing needs a force-push
   against anyone else's history, so the history rewrite of `releases/` is a
   "announce it here, do it once" decision rather than a diplomatic one. Do not rename
   published module coordinates anyway: third-party modules from the 2007-2011 era should
   keep loading against this build.
2. **Keep macOS, drop Quaqua.** The JDK floor stays 17 (CI also runs 21 and 25), and the
   macOS work is the three-step plan in Workstream 3: system Aqua look and feel,
   `java.awt.desktop` instead of `com.apple.eawt` reflection, then re-check the resize
   path. Quaqua 3.x from 2011 is the direct cause of the macOS start-up failures, so
   removing it is both the fix and the removal of the oldest dependency in `lib/`.
   **The OS floor is macOS 14 (2023)** and the work is planned against the 2023-2027
   window: macOS 26 has no Quaqua L&F anyway, so the 2007 look was already gone on current
   systems. Pen input on Apple Silicon depends on Rosetta and therefore inherits Apple's
   own deadline — Rosetta through macOS 27, reduced to "certain older, unmaintained games"
   in macOS 28 — so it is best-effort, and no macOS platform work should be sold as making
   the tablet work on a current Mac.
3. **Pen-tablet input stays.** JPen and its native libraries are vendored and shipped, and
   the single-archive decision means all three platforms' natives travel together. If a
   future release wants per-platform archives, differentiate on those natives first and
   assert the result - see Workstream 1b. Coverage on macOS is weak and should be stated
   rather than assumed: `lib/jpen/*.jnilib` is `x86_64` + `i386` + `ppc` with **no `arm64`
   slice**, so the arm64 CI runners cannot start JPen and the published archive can only be
   expected to deliver pressure under an `x86_64` JVM through Rosetta.
4. **Android is parked, with the measurement recorded so it is not re-opened cheaply.** 66
   of 91 files (82% of LOC) import `javax.swing`; `JPanel`/`JDialog`/`BorderLayout`/
   `JOptionPane` and modal dialogs are load-bearing, `BufferedImage` is the canvas, PDF
   export goes through iText and `java.awt.print`, and JPF is desktop classloader
   technology. Only 11 files (5.6%) import neither AWT nor Swing. An Android release is a
   new application reusing Alchemy's ideas and file format, not a port of this code: a UI
   port is months, a core extraction that keeps modules loading is more. Out of scope until
   someone intends that rewrite.
