(ns dyna-spec.main
  (:require [dyna-spec.fn-sampler :as fn-sampler]
            [dyna-spec.docs :as docs])
  (:import [clojure.storm Tracer Emitter]))

(defn- setup-storm []
  (Emitter/setInstrumentationEnable true)

  (Emitter/setFnCallInstrumentationEnable true)
  (Emitter/setFnReturnInstrumentationEnable true)
  (Emitter/setExprInstrumentationEnable false)
  (Emitter/setBindInstrumentationEnable false)

  (Tracer/setTraceFnsCallbacks
   {:trace-fn-call-fn (fn [_ fn-ns fn-name fn-args-vec form-id]
                        (fn-sampler/trace-fn-call {:form-id form-id
                                                   :ns      fn-ns
                                                   :fn-name fn-name
                                                   :fn-args fn-args-vec}))
    :trace-fn-return-fn (fn [_ ret-val _ _]
                          (fn-sampler/trace-fn-return ret-val))}))

(defn- symb-var-meta [vsymb]
  (when vsymb
    (let [v (find-var vsymb)]
      (-> (meta v)
          (select-keys [:added :ns :name :file :static :column :line :arglists :doc])
          (update :ns (fn [ns] (when ns (ns-name ns))))
          (update :arglists str)))))

(defn- add-vars-meta [fns-map]
  (let [total-cnt (count fns-map)]

    (println (format "Adding vars meta for a fns-map of size %d" total-cnt))

    (reduce-kv (fn [r fsymb fdata]
                 (let [data (assoc fdata :var-meta (symb-var-meta fsymb))]
                   (assoc r fsymb data)))
               {}
               fns-map)))

(defn run

  "Run with clj -X:dyna-spec dyna-spec/run :jar-name \"my-app\" :test-fn dev-tester/run-test

  TODO
      "

  [{:keys [test-fn test-fn-args]
    :or {test-fn-args []}
    :as opts}]

  (setup-storm)

  (fn-sampler/init-state)

  (let [tfn (requiring-resolve test-fn)]
    (println "Running all tests via " test-fn)
    (apply tfn test-fn-args))

  (println "Tests done.")

  (-> (fn-sampler/get-sampled-fns opts)
      add-vars-meta
      (docs/write-docs opts))

  (println "All done."))

(comment
  (require '[dev-tester])

  (run {:test-fn 'dev-tester/run-test
        :jar-name "dev-tester"})
  )

;; clj -Sforce -Sdeps '{:deps {docs/docs {:local/root "/home/jmonetta/my-projects/dyna-spec/dev-tester.jar"}} :aliases {:dev {:classpath-overrides {org.clojure/clojure nil} :extra-deps {com.github.flow-storm/clojure {:mvn/version "RELEASE"} com.github.flow-storm/flow-storm-dbg {:mvn/version "RELEASE"}}}}}' -A:dev
