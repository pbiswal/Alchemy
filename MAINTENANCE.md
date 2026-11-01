# Alchemy maintenance plan

Status: **planning / pre-feature**
Scope: restore a reproducible, headless-verifiable build and stabilise the repo so
feature work can resume. No feature or behaviour changes land on this branch.

Branch: `chore/revive-build-and-ci` (from `master` @ `1a4ac99`)

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
      XMLCommons/`xml-apis`. Keep vendored: JPF 1.5.1, JPen (+ natives), Quaqua, Foxtrot.
- [ ] Evaluate `lib/itext.jar` (unlabelled vintage) against OpenPDF before touching
      PDF export; PDF export is a user-visible feature, do not change behaviour here.
- [ ] Delete the developer-local leftover in `nbproject/project.properties`:
      `file.reference.foxtrot.jar-1=../../../../Sony/Exhibition Workshop/...`
- [x] `run` and `smokeTest` tasks exist. `smokeTest` boots the real app under a private
      X display and asserts: no fatal start-up error, window/canvas sized, menu bar and
      tool bar populated (48 menu items, 18 tool buttons), 28 archives -> 27 plugins
      (16 create + 11 affect), and no unexpected exception trace on stdout/stderr.
- [x] GitHub Actions matrix: ubuntu / macos / windows x JDK 17 / 21 / 25
      (`.github/workflows/ci.yml`), jobs = `build` + `smokeTest` (Xvfb on Linux,
      runner window session on macOS/Windows), `smoke-result.txt` and the app log
      uploaded on failure. No deploy. The smoke step also prints the harness verdict
      into the job summary and raises a `::error::` annotation carrying the failing
      exception and its source location, because Actions job logs need a token to
      read and fork CI runs get debugged from outside. macOS smoke is
      `continue-on-error` - see the macOS note below; macOS compile/package stays a
      real gate.
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
- [ ] Retire the dead macOS paths: `core/OSXAdapter` and the `com.apple.eawt` /
      `com.apple.eio.FileManager` reflection do nothing on modern macOS.
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

## Open questions

1. Is this a personal fork for one contributor, or should the revival be coordinated
   upstream (force-push and history rewrite affect every existing fork)?
2. Which JDK do we commit to supporting, and does macOS/Quaqua support still matter
   (Quaqua is the oldest-looking dependency in `lib/`)?
3. Is the JPen pen-tablet path still a requirement? It is the main reason natives ship
   in `lib/jpen`, and it is the least likely thing to survive a platform update.
