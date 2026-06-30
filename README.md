# langchain-clj

LangChain-core-style LLM foundation layer in **portable Clojure** —
zero dependencies, every namespace is `.cljc`, designed to run on
**Clojure-on-WASM hosts** (SCI, ClojureScript, GraalVM, kotoba-clj) as
well as the JVM, with all state persisted through a **Datomic API**.

[langgraph-clj](https://github.com/kotoba-lang/langgraph)
builds graph orchestration (StateGraph, checkpointers, ReAct agent) on
top of this library — the same layering as upstream langchain-core /
langgraph.

```
src/langchain/
  runnable.cljc    LCEL Runnables: pipe / parallel / branch / retry / fallbacks
  message.cljc     chat message data model (Anthropic-shaped maps)
  prompt.cljc      {var} templates, chat templates, placeholders
  model.cljc       ChatModel protocol, mock model, Anthropic + OpenAI-compatible
                   adapters (OpenAI / Ollama / Gemini; I/O injected)
  tool.cljc        tool definitions + execution + wire-format conversion
  parser.cljc      str / edn / json output parsers
  memory.cljc      chat history as datoms
  db.cljc          Datomic-API-compatible EAV store + Datalog + pull (swappable)
```

## Design

- **WASM premise** — no JVM interop, no threads, no wall clock. The
  library does no I/O: HTTP and JSON are *injected host capabilities*
  (fetch on a WASM host, any client on the JVM).
- **Datomic API premise** — chat history is datoms. A minimal
  Datomic-compatible store (datalog `q`, `pull`, upsert,
  cardinality-many, lookup refs, `as-of`) is bundled; real Datomic
  Local or DataScript drops in via the `langchain.db/api` function
  map. Cross-thread views are Datalog queries (ADR-0010 pattern).

## Quickstart

```clojure
(require '[langchain.runnable :as r]
         '[langchain.prompt :as prompt]
         '[langchain.parser :as parser]
         '[langchain.model :as model]
         '[langchain.message :as msg])

;; LCEL — plain fns, keywords, and maps are already Runnables
(def claude
  (model/anthropic-model
   {:api-key API-KEY
    :model "claude-opus-4-8"
    :http-fn host-fetch            ; injected host capability
    :json-write … :json-read …}))  ; defaults to js/JSON on cljs

;; or any OpenAI-compatible backend — OpenAI, local Ollama, Gemini:
(def gemma
  (model/openai-model
   {:url "http://localhost:11434/v1/chat/completions"   ; Ollama, no api-key
    :model "hf.co/unsloth/gemma-4-E4B-it-qat-GGUF:UD-Q4_K_XL"
    :http-fn host-fetch
    :json-write … :json-read …}))

(def chain
  (r/pipe (prompt/chat-template
           [:system "You translate to {lang}."]
           [:placeholder :history]
           [:user "{text}"])
          (model/as-runnable claude)
          (parser/str-parser)))

(r/invoke chain {:lang "French" :text "hello" :history []})

;; tool calling
(def weather
  {:name "get_weather"
   :description "Get current weather for a location"
   :schema {:type "object"
            :properties {:location {:type "string"}}
            :required ["location"]}
   :fn (fn [{:keys [location]}] …)})

(model/-generate (model/bind-tools claude [weather])
                 [(msg/user "Weather in Paris?")] {})
;; => {:role :assistant :tool-calls [{:id … :name "get_weather" …}] …}

;; chat history as datoms
(require '[langchain.memory :as memory] '[langchain.db :as db])
(def conn (db/create-conn memory/memory-schema))
(def hist (memory/datomic-chat-history conn))
((:append! hist) "thread-1" (msg/user "hello"))
(db/q '[:find ?tid (count ?m)
        :where [?t :thread/id ?tid] [?m :msg/thread ?t]]
      (db/db conn))
```

## Mapping from upstream

See [docs/adr/0001-architecture.md](docs/adr/0001-architecture.md) for
the langchain-core → langchain-clj correspondence table and the
zero-dependency / injected-I/O rationale.

## Tests / example

```sh
clojure -M:test                                  # 15 tests, 59 assertions
clojure -Sdeps '{:paths ["src" "examples"]}' \
        -M -e "(require 'chain) (chain/-main)"
```
