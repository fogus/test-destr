(ns build
  (:require [destr.gen :as gen]))

(defn generate
  "Generate the destructuring regression test file from src/resources/destr.edn.
   Accepts an optional :path key to override the default output location.

   Usage:
     clj -T:build generate
     clj -T:build generate :path '\"test/destr/gen_test.clj\"'"
  [{:keys [path] :or {path "test/destr/gen_test.clj"}}]
  (let [result (gen/generate! path)]
    (println (str "Generated " (:written result) " tests -> " (:path result)))))
