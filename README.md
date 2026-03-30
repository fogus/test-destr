# test-destr

A regression test suite for Clojure's `destructure` function that establishes a behavioral baseline against which future changes to that function can be verified.

## Usage

Run the generated test suite against the current version of Clojure:

```
clj -M:test
```

To test against a different version of Clojure, change the `org.clojure/clojure` version in the top-level `:deps` map in `deps.edn`.

## Generating the Tests

The test source file is generated from a recorded set of `destructure` inputs and outputs stored in `src/resources/destr.edn`. To regenerate it:

```
clj -T:build generate
```

This writes `test/destr/gen_test.clj`. An optional `:path` argument overrides the output location:

```
clj -T:build generate :path '"test/destr/gen_test.clj"'
```

Generation can also be run interactively from the REPL in the `destr.gen` namespace:

```clojure
;; Full pipeline in one shot
(generate!)

;; Or step by step
(find-readable-forms!)
(write-test-file!)
```

## Rationale and How It Works

Clojure's `destructure` function is a compiler-level primitive that underpins `let`, `fn`, `loop`, and every other binding form in the language. Its behavior is rarely tested directly, which means subtle regressions can go unnoticed across Clojure releases. This repository records a large corpus of known-good `destructure` inputs and outputs and turns them into executable tests, making it straightforward to check whether a new version of Clojure preserves the existing behavior.

### How the generator works

The file `src/resources/destr.edn` was produced by an instrumented version of `destructure` that recorded each call's input binding form and the resulting output binding vector as strings. The generator in `destr.gen` processes this file in three stages:

1. **Reading.** Each entry is parsed with `read-string`. Entries that cannot be read at all (due to unresolvable tagged literals or other reader errors) are collected in the `bad-reads` atom and skipped.

2. **Filtering.** Successfully read forms are checked by `is-comparable?`, which walks the entire form looking for values that cannot be reliably compared with `=`. Any form containing a regex (`java.util.regex.Pattern`) or `##NaN` (`Double/NaN`) is moved to the `uncomparable-reads` atom. Regexes do not implement value equality in Java, so two independently constructed patterns with identical source strings are not `=`. `##NaN` is by IEEE 754 definition not equal to itself, so any assertion involving it would fail regardless of correctness.

3. **Code generation.** For each entry that survives both filters (collected in `good-reads`), a `deftest` form is generated. The stored output is normalized at *generation time* by `normalize-destructuring`, which replaces the numeric gensym suffixes that `destructure` produces (e.g. `map__7291`) with stable logical variables (e.g. `?map1`). This normalization is performed using `clojure.core.unify`: `u/extract-lvars` walks the form and collects every symbol whose name contains `__` (the gensym marker), and `u/make-subst-fn` builds a substitution function that rewrites those symbols throughout the form. The gensym names found in any given form are sorted before numbering, so the mapping from gensym to logical variable is always assigned in the same order regardless of which numeric suffix the compiler happened to choose. This makes the resulting logical variable names (e.g. `?map1`, `?seq2`) deterministic and comparable across separate calls to `destructure`. The normalized result is embedded directly as a quoted literal in the test, so no normalization work happens at test runtime for the expected value. At test runtime, only the fresh call to `destructure` needs to be normalized before comparison:

   ```clojure
   (deftest test-destructure-42
     (let [expected '[x ?seq1 ?seq2 (clojure.core/seq ?seq1) ...]]
       (is (= expected (normalize-destructuring (destructure 'INPUT))))))
   ```

This design means the generated tests are self-contained and do not depend on the recorded output strings at runtime — only on `destructure` itself and the normalization logic in `destr.gen`.
