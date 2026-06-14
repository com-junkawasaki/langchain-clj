(ns langchain.model
  "ChatModel protocol + a mock model for tests + adapters for the
  Anthropic Messages API and OpenAI-compatible Chat Completions APIs
  (OpenAI / Ollama / Gemini).

  WASM premise: this library performs no I/O itself. The Anthropic
  adapter takes host capabilities as injected functions —

    :http-fn    (fn [{:keys [url method headers body]}]
                  → {:status int :body string})
    :json-write (fn [clj-map] → json string)
    :json-read  (fn [json-string] → clj map, keyword keys)

  On a JS/WASM host json defaults to js/JSON; on the JVM inject
  cheshire/data.json. :http-fn must always be injected (fetch on a
  WASM host, an HTTP client on the JVM)."
  (:require [langchain.runnable :as r]
            [langchain.tool :as tool]))

(defprotocol ChatModel
  (-generate [model messages opts]
    "messages → assistant message map (see langchain.message)."))

;; ChatModels are Runnables over a message vector.
(defrecord ModelRunnable [model]
  r/IRunnable
  (-invoke [_ input opts] (-generate model input opts))
  (-stream [_ input opts] [(-generate model input opts)]))

(defn as-runnable [model] (->ModelRunnable model))

(defn bind-tools
  "Returns a model that always passes the given tools."
  [model tools]
  (reify ChatModel
    (-generate [_ messages opts]
      (-generate model messages (assoc opts :tools tools)))))

;; ───────────────────────── mock model ─────────────────────────

(defn mock-model
  "Deterministic model for tests/offline runs. `responses` is a vector
  of assistant messages (or a fn messages→message); consumed in order,
  the last one repeats."
  [responses]
  (let [i (atom -1)]
    (reify ChatModel
      (-generate [_ messages opts]
        (if (fn? responses)
          (responses messages opts)
          (let [n (swap! i inc)]
            (nth responses (min n (dec (count responses))))))))))

;; ───────────────────────── Anthropic adapter ─────────────────────────

(def ^:private anthropic-url "https://api.anthropic.com/v1/messages")
(def default-model "claude-opus-4-8")

(defn- msg->anthropic [{:keys [role content tool-calls tool-call-id error?]}]
  (case role
    :system nil ; hoisted to top-level :system
    :tool {:role "user"
           :content [(cond-> {:type "tool_result"
                              :tool_use_id tool-call-id
                              :content content}
                       error? (assoc :is_error true))]}
    :assistant {:role "assistant"
                :content (vec (concat
                               (when (and content (not= content ""))
                                 [{:type "text" :text content}])
                               (for [{:keys [id name input]} tool-calls]
                                 {:type "tool_use" :id id :name name :input input})))}
    :user {:role "user" :content content}))

(defn- merge-consecutive
  "The API rejects consecutive same-role messages only loosely (it
  combines them), but tool_result blocks for one assistant turn must
  land in a single user message — merge adjacent same-role entries."
  [msgs]
  (reduce (fn [acc m]
            (let [prev (peek acc)]
              (if (and prev (= (:role prev) (:role m))
                       (vector? (:content prev)) (vector? (:content m)))
                (conj (pop acc) (update prev :content into (:content m)))
                (conj acc m))))
          [] msgs))

(defn request-body
  "Builds the Anthropic Messages API request body (a Clojure map) from
  langchain messages + opts. Exposed for testing."
  [messages {:keys [model max-tokens tools system] :as _opts}]
  (let [sys (or system
                (some #(when (= :system (:role %)) (:content %)) messages))
        body {:model (or model default-model)
              :max_tokens (or max-tokens 16000)
              :messages (->> messages
                             (keep msg->anthropic)
                             merge-consecutive
                             vec)}]
    (cond-> body
      sys (assoc :system sys)
      (seq tools) (assoc :tools (mapv tool/->anthropic tools)))))

(defn parse-response
  "Anthropic response map → assistant message. Exposed for testing."
  [{:keys [content stop_reason usage] :as resp}]
  (when (= "refusal" stop_reason)
    (throw (ex-info "Model refused request" {:type :refusal :response resp})))
  (let [text (apply str (keep #(when (= "text" (:type %)) (:text %)) content))
        calls (vec (keep #(when (= "tool_use" (:type %))
                            {:id (:id %) :name (:name %) :input (:input %)})
                         content))]
    (cond-> {:role :assistant :content text :stop-reason stop_reason}
      (seq calls) (assoc :tool-calls calls)
      usage (assoc :usage usage))))

;; ─────────────── OpenAI-compatible adapter (Ollama / Gemini / OpenAI) ───────────────

(def ^:private openai-url "https://api.openai.com/v1/chat/completions")

(defn- image-block->openai
  "Anthropic-style image block → OpenAI image_url part (data URI)."
  [{:keys [source]}]
  {:type "image_url"
   :image_url {:url (str "data:" (:media_type source)
                         ";base64," (:data source))}})

(defn- content->openai
  "string → string; Anthropic-style content blocks → OpenAI parts."
  [content]
  (if (string? content)
    content
    (mapv (fn [{:keys [type text] :as block}]
            (case type
              "text" {:type "text" :text text}
              "image" (image-block->openai block)
              block))
          content)))

(defn- msg->openai
  "langchain message → seq of OpenAI chat messages. The Chat Completions
  tool role is text-only, so a tool result carrying image blocks (e.g. a
  screenshot) becomes a text tool message plus a follow-up user message
  with the images."
  [json-write {:keys [role content tool-calls tool-call-id]}]
  (case role
    :system [{:role "system" :content content}]
    :user [{:role "user" :content (content->openai content)}]
    :assistant [(cond-> {:role "assistant"
                         :content (when (and content (not= content "")) content)}
                  (seq tool-calls)
                  (assoc :tool_calls
                         (vec (for [{:keys [id name input]} tool-calls]
                                {:id id :type "function"
                                 :function {:name name
                                            :arguments (json-write input)}}))))]
    :tool (let [blocks (when-not (string? content) content)
                images (filterv #(= "image" (:type %)) blocks)
                text (if (string? content)
                       content
                       (or (some #(when (= "text" (:type %)) (:text %)) blocks)
                           ""))]
            (cond-> [{:role "tool" :tool_call_id tool-call-id
                      :content (if (seq images)
                                 (str text " (screenshot in the next user message)")
                                 text)}]
              (seq images)
              (conj {:role "user" :content (mapv image-block->openai images)})))))

(defn openai-request-body
  "Builds the OpenAI Chat Completions request body (a Clojure map) from
  langchain messages + opts. Exposed for testing."
  [json-write messages {:keys [model max-tokens tools system] :as _opts}]
  (let [sys (when (and system (not-any? #(= :system (:role %)) messages))
              [{:role "system" :content system}])]
    (cond-> {:model model
             :messages (vec (concat sys (mapcat #(msg->openai json-write %) messages)))}
      max-tokens (assoc :max_tokens max-tokens)
      (seq tools) (assoc :tools (mapv tool/->openai tools)))))

(defn openai-parse-response
  "OpenAI Chat Completions response map → assistant message. Tool-call
  arguments are decoded with json-read. Exposed for testing."
  [json-read {:keys [choices usage] :as _resp}]
  (let [{:keys [message finish_reason]} (first choices)
        {:keys [content tool_calls]} message
        calls (vec (map-indexed
                    (fn [i {:keys [id function]}]
                      {:id (or id (str "call_" i))
                       :name (:name function)
                       :input (json-read (:arguments function))})
                    tool_calls))]
    (cond-> {:role :assistant :content (or content "") :stop-reason finish_reason}
      (seq calls) (assoc :tool-calls calls)
      usage (assoc :usage usage))))

(defn openai-model
  "OpenAI-compatible Chat Completions chat model — speaks to OpenAI,
  Ollama (http://localhost:11434/v1/chat/completions, no :api-key) and
  Gemini's OpenAI-compatible endpoint
  (https://generativelanguage.googleapis.com/v1beta/openai/chat/completions).

    (openai-model {:model \"hf.co/unsloth/gemma-4-E4B-it-qat-GGUF:UD-Q4_K_XL\"
                   :url \"http://localhost:11434/v1/chat/completions\"
                   :http-fn host-fetch
                   :json-write … :json-read …})"
  [{:keys [api-key model max-tokens http-fn json-write json-read url]
    :or {url openai-url
         #?@(:cljs [json-write (fn [m] (js/JSON.stringify (clj->js m)))
                    json-read (fn [s] (js->clj (js/JSON.parse s) :keywordize-keys true))])}}]
  (when-not model
    (throw (ex-info ":model is required (no portable default across backends)" {})))
  (when-not http-fn
    (throw (ex-info ":http-fn must be injected (host capability)" {})))
  (when-not (and json-write json-read)
    (throw (ex-info ":json-write/:json-read must be injected on this host" {})))
  (reify ChatModel
    (-generate [_ messages opts]
      (let [body (openai-request-body json-write messages
                                      (merge {:model model :max-tokens max-tokens} opts))
            {:keys [status] resp-body :body}
            (http-fn {:url url
                      :method :post
                      :headers (cond-> {"content-type" "application/json"}
                                 api-key (assoc "authorization" (str "Bearer " api-key)))
                      :body (json-write body)})]
        (when-not (and status (<= 200 status 299))
          (throw (ex-info "OpenAI-compatible API error" {:status status :body resp-body})))
        (openai-parse-response json-read (json-read resp-body))))))

(defn anthropic-model
  "Anthropic Messages API chat model.

    (anthropic-model {:api-key …
                      :model \"claude-opus-4-8\"
                      :http-fn host-fetch
                      :json-write … :json-read …})"
  [{:keys [api-key model max-tokens http-fn json-write json-read url]
    :or {model default-model
         url anthropic-url
         #?@(:cljs [json-write (fn [m] (js/JSON.stringify (clj->js m)))
                    json-read (fn [s] (js->clj (js/JSON.parse s) :keywordize-keys true))])}}]
  (when-not http-fn
    (throw (ex-info ":http-fn must be injected (host capability)" {})))
  (when-not (and json-write json-read)
    (throw (ex-info ":json-write/:json-read must be injected on this host" {})))
  (reify ChatModel
    (-generate [_ messages opts]
      (let [body (request-body messages (merge {:model model :max-tokens max-tokens} opts))
            {:keys [status] resp-body :body}
            (http-fn {:url url
                      :method :post
                      :headers {"content-type" "application/json"
                                "x-api-key" api-key
                                "anthropic-version" "2023-06-01"}
                      :body (json-write body)})]
        (when-not (and status (<= 200 status 299))
          (throw (ex-info "Anthropic API error" {:status status :body resp-body})))
        (parse-response (json-read resp-body))))))
