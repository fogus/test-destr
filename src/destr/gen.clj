(ns destr.gen
  (:require [clojure.core.unify :as u]
            [clojure.java.io :as jio]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]))

(def good-reads (atom {}))
(def bad-reads (atom {}))
(def uncomparable-reads (atom {}))

(def raw-destr (edn/read-string (slurp (jio/resource "resources/destr.edn"))))

(defn is-comparable?
  "Returns false if form contains any value that cannot be compared with =,
   specifically: regex patterns and ##NaN."
  [form]
  (not (some #(or (instance? java.util.regex.Pattern %)
                  (and (instance? Double %) (Double/isNaN %)))
             (tree-seq coll? seq form))))

(defn find-readable-forms! []
  (doseq [[k v] raw-destr]
    (try
      (let [rk (read-string k)
            rv (read-string v)]
        (if (and (is-comparable? rk) (is-comparable? rv))
          (swap! good-reads assoc rk rv)
          (swap! uncomparable-reads assoc rk rv)))
      (catch Exception _
        (swap! bad-reads assoc k v))))
  {:good         (count @good-reads)
   :uncomparable (count @uncomparable-reads)
   :bad          (count @bad-reads)})

(defn destructure-name? [x]
  (and (symbol? x)
       (clojure.string/includes? (name x) "__")))

(defn gs->lv [sym n]
  (let [front (first (clojure.string/split (name sym) #"__"))]
    (symbol (str "?" front n))))

(defn build-subst-map [gses]
  (let [n (atom 0)]
    (reduce (fn [acc gs]
              (assoc acc gs (gs->lv gs (swap! n inc))))
            (sorted-map)
            gses)))

(def subst-gs (u/make-subst-fn destructure-name?))

(defn normalize-destructuring [d]
  (->> d
       (u/extract-lvars destructure-name?)
       vec
       sort
       build-subst-map
       (subst-gs d)))

;; --- Test generation ---

(defn generate-test-form
  "Build a single deftest form (as data) for the nth input/output pair.
   normalize-destructuring is called at generation time; the result is
   embedded as a quoted literal so no runtime normalization of the
   expected value is needed."
  [n input output]
  (let [expected (normalize-destructuring output)]
    (list 'deftest (symbol (str "test-destructure-" n))
          (list 'let ['expected (list 'quote expected)]
                (list 'is (list '= 'expected
                                (list 'normalize-destructuring
                                      (list 'destructure (list 'quote input)))))))))

(defn generate-all-tests
  "Return a seq of deftest forms derived from good-reads."
  []
  (map-indexed (fn [n [input output]]
                 (generate-test-form n input output))
               @good-reads))

(def ^:private test-ns-decl
  '(ns destr.gen-test
     (:require [clojure.test :refer [deftest is]]
               [destr.gen :refer [normalize-destructuring]])))

(defn write-test-file!
  "Write all generated tests to path (default: test/destr/gen_test.clj).
   Expects good-reads to already be populated via find-readable-forms!."
  ([] (write-test-file! "test/destr/gen_test.clj"))
  ([path]
   (let [test-forms (doall (generate-all-tests))
         out-file   (jio/file path)]
     (jio/make-parents out-file)
     (with-open [w (jio/writer out-file)]
       (binding [*out* w]
         (pp/pprint test-ns-decl)
         (newline)
         (doseq [form test-forms]
           (pp/pprint form)
           (newline))))
     {:written (count test-forms)
      :path    (.getAbsolutePath out-file)})))

(defn generate!
  "Full pipeline: populate good-reads from EDN, then write the test file.
   Optionally accepts a target path."
  ([] (generate! "test/destr/gen_test.clj"))
  ([path]
   (find-readable-forms!)
   (write-test-file! path)))

(comment

  ;; Run the full pipeline in one shot:
  (generate!)

  ;; Or step by step:
  (find-readable-forms!)
  (write-test-file!)

  ;; Inspect a sample
  (def rf-in (-> @good-reads first first))
  (def rf-out (-> @good-reads first second))

  (destructure '[x 42, {:keys [a b]} a-map])

  (def dsf
    '[x 42 map__7295
      a-map map__7295 (if (clojure.core/seq? map__7295)
                        (if (clojure.core/next map__7295)
                          (clojure.lang.PersistentArrayMap/createAsIfByAssoc
                           (clojure.core/to-array map__7295))
                          (if (clojure.core/seq map__7295)
                            (clojure.core/first map__7295)
                            clojure.lang.PersistentArrayMap/EMPTY))
                        map__7295)
      a (clojure.core/get map__7295 :a)
      b (clojure.core/get map__7295 :b)])

  (normalize-destructuring (destructure rf-in))

)
