(ns langchain.kotoba-db-test
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.kotoba-db :as kdb]))

;; ─── mock host-caps ──────────────────────────────────────────────────────────

(defn- nsid-from-url [url]
  (last (str/split url #"/xrpc/")))

(defn- mock-caps
  "Returns host-caps that capture every request and return canned responses.

  respond-fn: (fn [nsid parsed-body] => response-clj-map-with-keyword-keys)
  captured:   atom that collects {:nsid :body} per call."
  [captured respond-fn]
  {:http-fn
   (fn [{:keys [url body]}]
     (let [nsid    (nsid-from-url url)
           ;; json-write is pr-str in test so body is EDN
           parsed  (edn/read-string body)
           resp    (respond-fn nsid parsed)]
       (swap! captured conj {:nsid nsid :body parsed})
       ;; json-read is edn/read-string in test, so body must be pr-str'd
       {:status 200 :body (pr-str resp)}))
   :json-write pr-str
   :json-read  edn/read-string})

(def ^:private test-conn
  (kdb/kotoba-conn "http://kotoba.test:8080" "k51testgraph" {:token "test-token"}))

;; ─── transact! ───────────────────────────────────────────────────────────────

(deftest transact!-sends-correct-xrpc
  (let [captured (atom [])
        caps     (mock-caps captured
                            (fn [_nsid _body]
                              {:status "ok" :graph "k51testgraph"
                               :tx_cid "cid1" :commit_cid "cid2"
                               :ipns_name "k51testgraph" :ipns_sequence 1
                               :ipns_valid_until "2099-01-01"
                               :index_roots {} :datom_count 2
                               :journal_cids [] :tempids {} :datoms []}))
        api      (kdb/kotoba-api caps)
        tx-data  [{:db/id "-1" :checkpoint/key "t1/0"
                   :checkpoint/step 0 :checkpoint/thread "t1"
                   :checkpoint/state "{}" :checkpoint/frontier "[]"
                   :checkpoint/status :running}]
        result   ((:transact! api) test-conn tx-data)]
    (testing "posts to correct NSID"
      (is (= "ai.gftd.apps.kotobase.datomic.transact" (:nsid (first @captured)))))
    (testing "sends graph and tx_edn"
      (let [body (:body (first @captured))]
        (is (= "k51testgraph" (:graph body)))
        (is (string? (:tx_edn body)))))
    (testing "returns tx-report stub"
      (is (= tx-data (:tx-data result))))))

;; ─── :db is identity ─────────────────────────────────────────────────────────

(deftest db-returns-conn
  (let [api (kdb/kotoba-api (mock-caps (atom []) (fn [_ _] {})))]
    (is (= test-conn ((:db api) test-conn)))))

;; ─── q scalar find ───────────────────────────────────────────────────────────

(deftest q-scalar-find
  (let [captured (atom [])
        caps     (mock-caps captured
                            (fn [_nsid _body]
                              {:graph "k51testgraph" :basis_t nil
                               ;; scalar result: one row, one cell → EDN string "5"
                               :rows_edn [["5"]]}))
        api  (kdb/kotoba-api caps)
        step ((:q api) '[:find (max ?step) .
                         :in $ ?tid
                         :where [?e :checkpoint/thread ?tid]
                                [?e :checkpoint/step ?step]]
                       test-conn "thread-1")]
    (testing "posts to datomic.q"
      (is (= "ai.gftd.apps.kotobase.datomic.q" (:nsid (first @captured)))))
    (testing "sends graph, query_edn, inputs_edn"
      (let [body (:body (first @captured))]
        (is (= "k51testgraph" (:graph body)))
        (is (string? (:query_edn body)))
        (is (= ["\"thread-1\""] (:inputs_edn body)))))
    (testing "projects scalar result"
      (is (= 5 step)))))

;; ─── q collection find ───────────────────────────────────────────────────────

(deftest q-collection-find
  (let [caps (mock-caps (atom [])
                        (fn [_nsid _body]
                          {:graph "k51testgraph" :basis_t nil
                           :rows_edn [["\"eid-1\""] ["\"eid-2\""]]}))
        api  (kdb/kotoba-api caps)
        eids ((:q api) '[:find [?e ...]
                         :in $ ?tid
                         :where [?e :checkpoint/thread ?tid]]
                       test-conn "thread-1")]
    (testing "projects collection result"
      (is (= ["eid-1" "eid-2"] eids)))))

;; ─── q relation find ─────────────────────────────────────────────────────────

(deftest q-relation-find
  (let [caps (mock-caps (atom [])
                        (fn [_nsid _body]
                          {:graph "k51testgraph" :basis_t nil
                           ;; EDN cells: ":a" parses as keyword :a
                           :rows_edn [["1" ":a"] ["2" ":b"]]}))
        api  (kdb/kotoba-api caps)
        res  ((:q api) '[:find ?e ?a :where [?e ?a _]] test-conn)]
    (testing "projects relation as set of tuples"
      (is (= #{[1 :a] [2 :b]} res)))))

;; ─── pull ────────────────────────────────────────────────────────────────────

(deftest pull-lookup-ref
  (let [captured  (atom [])
        entity-edn (pr-str {:checkpoint/key "t1/5"
                            :checkpoint/thread "t1"
                            :checkpoint/step 5
                            :checkpoint/state "{}"
                            :checkpoint/frontier "[]"
                            :checkpoint/status :running})
        caps      (mock-caps captured
                             (fn [_nsid _body]
                               {:graph "k51testgraph" :basis_t nil
                                :entity "t1/5"
                                :entity_edn entity-edn
                                :datom_count 5 :datoms []}))
        api    (kdb/kotoba-api caps)
        result ((:pull api) test-conn '[*] [:checkpoint/key "t1/5"])]
    (testing "posts to datomic.pull"
      (is (= "ai.gftd.apps.kotobase.datomic.pull" (:nsid (first @captured)))))
    (testing "sends entity as EDN pr-str of lookup ref"
      (is (= "[:checkpoint/key \"t1/5\"]"
             (:entity (:body (first @captured))))))
    (testing "parses entity_edn to Clojure map"
      (is (= 5 (:checkpoint/step result)))
      (is (= "t1" (:checkpoint/thread result))))))

;; ─── entid ───────────────────────────────────────────────────────────────────

(deftest entid-lookup
  (let [caps   (mock-caps (atom [])
                          (fn [_nsid _body]
                            {:graph "k51testgraph"
                             :ident_edn ":checkpoint/key"
                             :basis_t nil
                             :entity "bafyeid123"}))
        api    (kdb/kotoba-api caps)
        result ((:entid api) test-conn :checkpoint/key)]
    (is (= "bafyeid123" result))))

;; ─── error propagation ───────────────────────────────────────────────────────

(deftest error-on-non-200
  (let [caps {:http-fn   (fn [_] {:status 401 :body "{\"error\":\"unauthorized\"}"})
              :json-write pr-str
              :json-read  edn/read-string}
        api  (kdb/kotoba-api caps)]
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
         #"kotoba XRPC error 401"
         ((:transact! api) test-conn [])))))

;; ─── authorization header ────────────────────────────────────────────────────

(deftest bearer-token-forwarded
  (let [captured (atom [])
        caps     {:http-fn   (fn [req]
                               (swap! captured conj req)
                               {:status 200
                                :body   (pr-str {:status "ok" :tx_cid "x"
                                                 :commit_cid "y" :graph "g"
                                                 :ipns_name "k" :ipns_sequence 0
                                                 :ipns_valid_until "" :index_roots {}
                                                 :datom_count 0 :journal_cids []
                                                 :tempids {} :datoms []})})
                 :json-write pr-str
                 :json-read  edn/read-string}
        api (kdb/kotoba-api caps)]
    ((:transact! api) test-conn [])
    (is (= "Bearer test-token"
           (get-in (first @captured) [:headers "authorization"])))))

;; ─── CACAO auth ──────────────────────────────────────────────────────────────

(deftest cacao-auth-forwarded
  (let [captured (atom [])
        cacao-conn (kdb/kotoba-conn "http://kotoba.test:8080" "k51testgraph"
                                    {:cacao "base64cacaoABC" :did "did:key:zABC"})
        caps {:http-fn   (fn [req]
                           (swap! captured conj req)
                           {:status 200
                            :body   (pr-str {:status "ok" :tx_cid "x"
                                             :commit_cid "y" :graph "g"
                                             :ipns_name "k" :ipns_sequence 0
                                             :ipns_valid_until "" :index_roots {}
                                             :datom_count 0 :journal_cids []
                                             :tempids {} :datoms []})})
              :json-write pr-str
              :json-read  edn/read-string}
        api (kdb/kotoba-api caps)]
    ((:transact! api) cacao-conn [])
    (testing "sends CACAO authorization header"
      (is (= "CACAO base64cacaoABC"
             (get-in (first @captured) [:headers "authorization"]))))
    (testing "sends x-kotoba-did header"
      (is (= "did:key:zABC"
             (get-in (first @captured) [:headers "x-kotoba-did"]))))
    (testing "sends cacao_b64 in request body"
      (let [body (edn/read-string (:body (first @captured)))]
        (is (= "base64cacaoABC" (:cacao_b64 body)))))))

;; ─── kg-ingest! ──────────────────────────────────────────────────────────────

(deftest kg-ingest!-sends-correct-request
  (let [captured (atom [])
        caps     (mock-caps captured
                            (fn [_nsid _body]
                              {:ok true :entity_cid "cid-x"
                               :kind "langgraph/checkpoint" :quad_count 6}))
        entity   {:id     "thread-A/0"
                  :kind   "langgraph/checkpoint"
                  :claims [{:pred "thread" :value "thread-A"}
                           {:pred "step"   :value "0"}]}]
    (kdb/kg-ingest! caps test-conn entity)
    (testing "posts to correct NSID"
      (is (= "ai.gftd.apps.kotobase.kg.ingest" (:nsid (first @captured)))))
    (testing "sends id and kind"
      (let [body (:body (first @captured))]
        (is (= "thread-A/0" (:id body)))
        (is (= "langgraph/checkpoint" (:kind body)))))
    (testing "sends claims"
      (is (= [{:pred "thread" :value "thread-A"}
              {:pred "step"   :value "0"}]
             (:claims (:body (first @captured))))))))
