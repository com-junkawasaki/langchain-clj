(ns langchain.kotoba-db
  "Kotoba-server XRPC backend implementing the langchain.db/api contract.

  Instead of an in-process EAV store, every operation is a POST to a
  running kotoba-server pod via the datomic.* XRPC namespace:

    ai.gftd.apps.kotobase.datomic.transact  → :transact!
    ai.gftd.apps.kotobase.datomic.q         → :q
    ai.gftd.apps.kotobase.datomic.pull      → :pull
    ai.gftd.apps.kotobase.datomic.entid     → :entid

  Pass the kotoba-api map as :db-api to datomic-checkpointer,
  langchain.memory/datomic-chat-history, etc.:

    (require '[langchain.kotoba-db    :as kdb]
             '[langgraph.checkpoint   :as cp])

    (def conn (kdb/kotoba-conn \"http://pod:8080\" \"k51...\"))
    (def api  (kdb/kotoba-api  jvm-host-caps))
    (def cp   (cp/datomic-checkpointer conn {:db-api api}))

  I/O is fully injected (ADR-0001) — host-caps shape matches langchain.model:
    {:http-fn   (fn [{:keys [url method headers body]}] => {:status n :body s})
     :json-write (fn [clj-data] => json-string)
     :json-read  (fn [json-string] => clj-data with keyword keys)}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]))

;; ─── connection map ───────────────────────────────────────────────────────────

(def KG-GRAPH-CID
  "Multibase CID of the kotobase-kg-v1 named graph (sha256 of b\"kotobase-kg-v1\").
  Pass as :graph to kotoba-conn when reading kg.ingest data via datomic.q."
  "bafyreiglzym7s24os6nki3aknbg2d6dncy5dadwjftavcnuarkdswz6afa")

(defn kotoba-conn
  "Creates a kotoba connection map (not an atom — state lives on the server).

   url   – kotoba-server base URL, e.g. \"http://149.28.207.62:8080\"
   graph – IPNS name or CID multibase of the target named graph
   opts  – :token  Bearer JWT
           :cacao  base64-CBOR CACAO string (for CACAO-gated endpoints)
           :did    DID string (required with :cacao; sent as x-kotoba-did)"
  ([url graph] (kotoba-conn url graph {}))
  ([url graph {:keys [token cacao did]}]
   (cond-> {:kotoba/url url :kotoba/graph graph}
     token (assoc :kotoba/token token)
     cacao (assoc :kotoba/cacao cacao :kotoba/did did))))

;; ─── private helpers ──────────────────────────────────────────────────────────

(defn- xrpc-url [conn nsid]
  (str (:kotoba/url conn) "/xrpc/" nsid))

(defn- req-headers [conn]
  (cond-> {"content-type" "application/json"
           "accept"       "application/json"}
    (:kotoba/token conn) (assoc "authorization"
                                (str "Bearer " (:kotoba/token conn)))
    (:kotoba/cacao conn) (-> (assoc "authorization"
                                    (str "CACAO " (:kotoba/cacao conn)))
                             (assoc "x-kotoba-did" (:kotoba/did conn)))))

(defn- post! [{:keys [http-fn json-write json-read]} conn nsid body]
  (let [resp (http-fn {:url     (xrpc-url conn nsid)
                       :method  :post
                       :headers (req-headers conn)
                       :body    (json-write body)})]
    (when-not (#{200 201} (:status resp))
      (throw (ex-info (str "kotoba XRPC error " (:status resp) ": " nsid)
                      {:nsid nsid :status (:status resp) :body (:body resp)})))
    (json-read (:body resp))))

;; ─── query projection ─────────────────────────────────────────────────────────

(defn- parse-find-spec [query]
  (let [q (if (map? query)
             query
             (->> (partition-by #{:find :in :where :with} query)
                  (partition 2)
                  (reduce (fn [m [[k] v]] (assoc m k (vec v))) {})))]
    (:find q)))

(defn- project-rows
  "Maps kotoba rows_edn (Vec<Vec<edn-string>>) to the same result shape
  as langchain.db/q: scalar value, collection vector, or set of tuples."
  [rows-edn find-spec]
  (let [rows    (mapv (fn [row] (mapv edn/read-string row)) rows-edn)
        scalar? (= '. (last find-spec))
        coll?   (and (= 1 (count find-spec))
                     (vector? (first find-spec))
                     (= '... (second (first find-spec))))]
    (cond
      scalar? (ffirst rows)
      coll?   (vec (distinct (map first rows)))
      :else   (set rows))))

;; ─── api map ─────────────────────────────────────────────────────────────────

(defn kotoba-api
  "Returns a langchain.db-compatible api map backed by kotoba-server XRPC.

   host-caps: {:http-fn   (fn [{:keys [url method headers body]}] => {:status n :body s})
               :json-write (fn [clj-data] => json-string)
               :json-read  (fn [json-string] => clj-data with keyword keys)}

   The returned map has the same keys as langchain.db/api:
     {:transact! :db :q :pull :entid}

   A Datomic Local or DataScript connection can also be used there;
   kotoba-api is the distributed, CID-pinned, CACAO-gated variant."
  [host-caps]
  {:transact!
   (fn [conn tx-data]
     (post! host-caps conn "ai.gftd.apps.kotobase.datomic.transact"
            (cond-> {:graph  (:kotoba/graph conn)
                     :tx_edn (pr-str (vec tx-data))}
              (:kotoba/cacao conn) (assoc :cacao_b64 (:kotoba/cacao conn))))
     ;; tx-report stub — checkpointer only needs side-effect, not tempids
     {:tx-data tx-data})

   ;; The conn map itself IS the db reference — all state lives on the server.
   ;; This matches the Datomic peer model: (d/db conn) returns a db value;
   ;; here the conn already identifies the remote graph head.
   :db identity

   :q
   (fn [query conn & inputs]
     (let [data (post! host-caps conn "ai.gftd.apps.kotobase.datomic.q"
                       {:graph      (:kotoba/graph conn)
                        :query_edn  (pr-str query)
                        ;; inputs_edn: non-$ bindings ($ is already the graph)
                        :inputs_edn (mapv pr-str inputs)})]
       (project-rows (:rows_edn data) (parse-find-spec query))))

   :pull
   (fn [conn pattern eid]
     (let [data (post! host-caps conn "ai.gftd.apps.kotobase.datomic.pull"
                       {:graph       (:kotoba/graph conn)
                        :entity      (pr-str eid)
                        :pattern_edn (pr-str pattern)})]
       ;; entity_edn is an EDN string of the Datomic entity map
       (edn/read-string (:entity_edn data))))

   :entid
   (fn [conn eid]
     (:entity (post! host-caps conn "ai.gftd.apps.kotobase.datomic.entid"
                     {:graph     (:kotoba/graph conn)
                      :ident_edn (pr-str eid)})))})

;; ─── kg.ingest surface ────────────────────────────────────────────────────────

(defn kg-ingest!
  "POST one entity to ai.gftd.apps.kotobase.kg.ingest.

   Writes to the kotobase-kg-v1 named graph (shared multi-tenant KG).
   Auth: Bearer JWT in conn is sufficient; CACAO is also forwarded when present.

   host-caps – {:http-fn :json-write :json-read}
   conn      – kotoba-conn (only url + auth are used; :kotoba/graph is ignored)
   entity    – {:id    string          ; unique entity ID, e.g. \"<thread>/<step>\"
                :kind  string          ; entity type, e.g. \"langgraph/checkpoint\"
                :claims [{:pred str :value str}]  ; free-form key-value pairs
                :relations [{:pred str :dst-id str}]  ; optional edges}"
  [host-caps conn entity]
  (post! host-caps conn "ai.gftd.apps.kotobase.kg.ingest"
         (cond-> {:id     (:id entity)
                  :kind   (:kind entity)
                  :claims (vec (:claims entity []))}
           (seq (:relations entity)) (assoc :relations (vec (:relations entity)))
           (:kotoba/cacao conn)      (assoc :cacao_b64 (:kotoba/cacao conn)))))
