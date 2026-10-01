# AGENTS.md

Notes for coding agents working on Babatype: a Monkeytype-style typing test
whose words are `clojure.core`'s public var names. A babashka app on
[gtkiccup](https://github.com/brdloush/gtkiccup) (hiccup to native GTK4), a git
dependency pinned to one commit in `bb.edn` and `deps.edn`.

## Layout

| path | what it is |
| --- | --- |
| `src/babatype/core.clj` | the app: state, view, keys, the caret, `-main` |
| `src/babatype/engine.clj` | the typing test as a pure state machine: keystrokes in, metrics out |
| `src/babatype/words.clj` | the word list, read from the running `clojure.core` |
| `src/babatype/css.clj` | the look, one CSS string |
| `src/babatype/desktop.clj` | `.desktop` file and icon install/uninstall |
| `dev/shot.clj` | `bb shot`: types a realistic run, then screenshots the app |
| `icons/` | `cz.brdloush.Babatype.png` (used by the header and the installer), `babatype-logo.svg` (source art) |
| `docs/design.md` | why things are built the way they are |

## Running things

```bash
bb babatype              # the app; starts maximized, esc quits
bb test                  # all tests, stops at the first failure
bb test/engine_test.clj  # one test file
bb shot babatype         # redraw docs/images/babatype.png (also: babatype-results)
bb dev                   # nREPL on port 1667
```

`engine_test`, `words_test`, `babatype_test` and `desktop_test` are pure.
`caret_test` and `inspector_test` open a real window, so they need a display.
A test file ends with `ALL OK`; a new one goes into the `test` task in `bb.edn`.

## REPL workflow

Prefer the REPL. `bb dev`, then `clj-nrepl-eval -p 1667`:

```clojure
(require '[babatype.core :as b] '[gtkiccup.core :as ui])
(def app (future (ui/run (b/app) :title "dev" :width 1100 :height 700
                         :window ui/chromeless-window
                         :css b/css
                         :on-render (fn [_ t] (b/place-caret! t))
                         :on-layout (fn [_ t] (b/place-caret! t)))))
(swap! b/state ...)      ; state changes re-render
(ui/refresh!)            ; after redefining a view fn
(ui/close!)
```

This skips `-main`'s maximize and its two threads; start `(b/tick!)` for the
clock and `(b/caret-tick!)` for the caret's slide animation. GTK calls only on the GTK thread: wrap widget reads in
`(ui/on-gtk-thread! #(...))`, or it segfaults. gtkiccup's `docs/repl.md` has
the rest.

## Things that are deliberate

Read `docs/design.md` before changing any of these.

- The passage is **one `GtkLabel`** with Pango markup, not a widget per
  character.
- Ligatures are turned off with Pango's `font_features` attribute. CSS
  `font-feature-settings` silently does nothing in GTK.
- Line breaks are computed by the engine (fixed 52-character lines), not by
  Pango's wrapping, so the passage never re-flows under the caret.
- The caret is a separate widget floated in an `:overlay`, placed from the
  label's Pango layout by `place-caret!`, at two moments: after each render
  (`:on-render`, exact once the label has been laid out) and after each painted
  frame (gtkiccup's `:on-layout`, the only moment that works for the first
  frame and after a resize). Keep both. It is animated by margins from
  `caret-tick!`. It finds its widgets in
  `(:tree @ui/current)` by CSS class.
- Keys are handled in the capture phase at the window, and buttons are
  `:focusable false`, so nothing can swallow the space bar.
- Space is just another character; mistakes count from the keystroke log, so
  backspace does not undo them.

## Keep the docs in step

`README.md` lists the modes, keys and requirements; `docs/design.md` explains
the passage, caret, ligatures, line breaks, results and scoring in detail.
**When you change how the app behaves, update both in the same change.** A
changed look also wants `bb shot babatype` and `bb shot babatype-results`.

When a fix belongs in the GUI layer rather than the app, make it in gtkiccup
(with its own docs and tests), not as a workaround here. Its checkout is
usually at `../gtkiccup`. To try a change there before it is pushed, point both
files at it with `{:local/root "../gtkiccup"}`. Once it is pushed, put the
git dependency back with the new `:git/sha`, in both files.
