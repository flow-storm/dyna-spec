(ns type-trek.docs
  (:require [type-trek.utils :as utils]
            [clojure.tools.build.api :as tools.build])
  (:import [java.io File]))

(def docs-file-name "flow-docs.edn")
(def deprecated-docs-file-name "samples.edn")

(defn- make-docs [fns-map]
  {:functions/data fns-map})

(defn write-docs

  "Save the resulting sampled FNS-MAP into a file and wrap it in a jar with RESULT-NAME"

  [fns-map {:keys [jar-name]}]
  (println "Saving result ...")

  (let [tmp-dir (.getAbsolutePath (utils/mk-tmp-dir!))
        result-file-str (-> (make-docs fns-map)
                            pr-str)
        result-file-path (str tmp-dir File/separator docs-file-name)]

    (println (format "Saving results in %s" result-file-path))
    (spit result-file-path result-file-str)

    (println (str "Wrote " result-file-path " creating jar file."))
    (tools.build/jar {:class-dir tmp-dir
                      :jar-file (str jar-name ".jar")})
    (println "Jar file created.")))
