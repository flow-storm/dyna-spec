(ns type-trek.fn-sampler

  "When instrumented namespaces code runs, the sampler will collect fns
  into a map {fully-qualified-fn-symbol-1 {:args-types #{...}
                                           :return-types #{...}
                                           :call-examples #{...}
                                           :var-meta {...}}
              fully-qualified-fn-symbol-2 {...}
              ...}

  end save it into a edn file then wrapped into a jar.

  :args-type is a set of vectors with sampled types descriptions.

  :return-types is a set of sampled types descriptions.

  :call-examples is a set of {:args [sv] :ret sv} where sv is the value serialization of the sampled value.
                 See `serialize-val` to see how values get serialized.

  :var-meta is meta as returned by Clojure (meta (var fully-qualified-fn-symbol))"

  (:require [type-trek.utils :as utils]
            [clojure.string :as str]
            [clojure.pprint :as pp])
  (:import [java.util ArrayDeque]
           [java.util.concurrent ConcurrentHashMap]))

(def max-samples-per-fn 3)
(def max-map-keys 20)

(def threads-stacks nil)
(def collected-fns  nil)

(defn type-name [o]
  (when o
    (.getName (class o))))

(defn- map-desc [m]
  (let [mdesc {:type/name (type-name m)
               :type/type :map}]
    (cond

      (keyword? (first (keys m)))
      (assoc mdesc
             :map/domain (->> (keys m)
                              (take 100) ;; should be enough for entities
                              (reduce (fn [r k]
                                        (assoc r k (type-name (get m k))))
                                      {}))
             :map/kind :entity)

      (and (some->> (keys m) (map class) (apply =))
           (some->> (vals m) (map class) (apply =)))
      (assoc mdesc
             :map/domain {(type-name (first (keys m))) (type-name (first (vals m)))}
             :map/kind :regular)

      :else mdesc)))

(defn- seqable-desc [xs]
  (let [first-elem-type (when (seq xs)
                          (let [first-elem (first xs)]
                            (if (utils/hash-map? first-elem)
                              (map-desc first-elem)
                              (type-name first-elem))))]
    (cond-> {:type/name (type-name xs)
             :type/type :seqable}
      first-elem-type (assoc :seq/first-elem-type first-elem-type))))

(defn type-desc

  "If `o` is non nil, returns a string description for the type of `o`"

  [o]

  (when o
    (cond
      (fn? o)
      {:type/type :fn}

      (utils/hash-map? o)
      (map-desc o)

      (and (seqable? o)
           (not (string? o))
           (counted? o))
      (seqable-desc o)

      :else
      {:type/name (type-name o)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Keep track of fns call stacks per thread  ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn push-thread-frame [^ConcurrentHashMap threads-stacks thread-id frame]
  (when-not (.containsKey threads-stacks thread-id)
    (.put threads-stacks thread-id (ArrayDeque.)))

  (let [th-stack (.get threads-stacks thread-id)]
    (.push ^ArrayDeque th-stack frame)))

(defn pop-thread-frame [^ConcurrentHashMap threads-stacks thread-id]
  (let [^ArrayDeque th-stack (.get threads-stacks thread-id)]
    (.pop th-stack)))

(defn peek-thread-frame [^ConcurrentHashMap threads-stacks thread-id]
  (let [^ArrayDeque th-stack (.get threads-stacks thread-id)]
    (.peek th-stack)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Instrumentation callbacks ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn trace-fn-call [fn-call-data]
  (push-thread-frame threads-stacks
                     (utils/get-current-thread-id)
                     fn-call-data))

(defn- collect-fn-call [fns-map {:keys [ns fn-name fn-args return]}]
  (if (str/includes? fn-name "fn--")
    ;; don't collect anonymous functions
    fns-map
    (let [fq-fn-symb (symbol ns fn-name)
          args-types-cnt (count (get-in fns-map [fq-fn-symb :args-types]))
          returns-type-cnt (count (get-in fns-map [fq-fn-symb :return-types]))
          call-examples-cnt (count (get-in fns-map [fq-fn-symb :call-examples]))
          args-types (when (< args-types-cnt max-samples-per-fn)
                       (mapv type-desc fn-args))
          return-type (when (< returns-type-cnt max-samples-per-fn)
                        (type-desc return))
          call-example (when (< call-examples-cnt max-samples-per-fn)
                         {:args fn-args
                          :ret return})]
      (cond-> fns-map
        args-types   (update-in [fq-fn-symb :args-types] (fnil conj #{}) args-types)
        return-type  (update-in [fq-fn-symb :return-types] (fnil conj #{}) return-type)
        call-example (update-in [fq-fn-symb :call-examples] (fnil conj #{}) call-example)))))

(defn trace-fn-return [return]
  (let [curr-thread-id (utils/get-current-thread-id)
        frame-data (peek-thread-frame threads-stacks curr-thread-id)]

    (swap! collected-fns collect-fn-call (assoc frame-data :return return))

    (pop-thread-frame threads-stacks curr-thread-id))
  return)

(defn- serialize-val [{:keys [examples-print-fn examples-print-length examples-print-level]} v]
  (binding [*print-length* examples-print-length
            *print-level* examples-print-level
            *print-readably* true]
    (try
      (str/replace (with-out-str (examples-print-fn v)) "..." ":...")
      (catch Exception _
        (println "Couldn't serialize val")
        "ERROR-SERIALIZING"))))

(defn- serialize-call-examples [collected-fns {:keys [examples-pprint? examples-print-length examples-print-level]}]
  (let [total-cnt (count collected-fns)
        ser-cfg {:examples-print-fn (if examples-pprint? pp/pprint print)
                 :examples-print-length (or examples-print-length 1)
                 :examples-print-level (or examples-print-level 2)}]

    (println (format "Processing call examples for %d collected fns. Serializing values ..." total-cnt))

    (update-vals
     collected-fns
     (fn [data]
       (update data :call-examples
               (fn [ce]
                 (->> ce
                      (map (fn [ex]
                             (-> ex
                                 (update :args #(mapv (partial serialize-val ser-cfg) %))
                                 (update :ret #(serialize-val ser-cfg %))))))))))))

(defn init-state []
  (alter-var-root #'threads-stacks (constantly (ConcurrentHashMap.)))
  (alter-var-root #'collected-fns (constantly (atom {}))))

(defn get-sampled-fns [opts]
  (serialize-call-examples @collected-fns opts))
