# ADR-0001: langchain-clj — portable Clojure, Datomic-API-first LLM core layer

- Status: Accepted (2026-06-11)
- 関連: langgraph-clj ADR-0001, kawasakijun ADR-0010 (Life Graph — EDN事実層 + Datalogビュー)

## 課題

LangChain (langchain-core) 相当の LLM 基盤レイヤを、

1. **Clojure WASM で動く前提**(SCI / CLJS / GraalVM / kotoba-clj いずれのホストでも)、
2. **Datomic API 前提**(チャット履歴を EAV ファクトとして保持、ADR-0010 と同型)

で実装したい。本家の積層(langchain-core が基盤、langgraph がその上)を
Clojure 側でも再現する: **langchain-clj が core、langgraph-clj がその上**。

## 決定

### 1. 本家 langchain-core との対応

| upstream (langchain-core) | langchain-clj |
|---|---|
| Runnable / LCEL (`|`, RunnableParallel, RunnableBranch, with_retry, with_fallbacks) | `langchain.runnable` — fn・map・keyword がそのまま Runnable |
| BaseMessage (Human/AI/System/Tool) | `langchain.message` — Anthropic シェイプの plain map |
| PromptTemplate / ChatPromptTemplate / MessagesPlaceholder | `langchain.prompt` |
| BaseChatModel / bind_tools | `langchain.model` — protocol + mock + Anthropic Messages API adapter |
| @tool / StructuredTool | `langchain.tool` — plain map {:name :description :schema :fn} |
| StrOutputParser / JsonOutputParser | `langchain.parser` (str / edn / json) |
| ChatMessageHistory / RunnableWithMessageHistory | `langchain.memory` — Datomic ファクトとしての履歴 |
| (該当なし — LangChain は状態をオブジェクトで持つ) | `langchain.db` — Datomic API 互換ミニ EAV ストア + Datalog |

### 2. 全コード .cljc・依存ゼロ・ホスト能力は注入

langgraph-clj ADR-0001 と同一の制約: ランタイム依存 0、JVM interop /
スレッド / wall clock / 乱数なし。HTTP・JSON はホスト能力として注入
(`anthropic-model {:http-fn … :json-write … :json-read …}`)。

### 3. Datomic API 前提

`langchain.db` は Datomic 互換 API(`create-conn / transact! / q / pull /
entity / entid / as-of`)の最小実装。チャット履歴(`langchain.memory`)は
thread entity + message entity の datom で、横断ビューは名前付き Datalog
クエリになる。本物の Datomic Local / DataScript は `langchain.db/api`
と同シェイプの関数マップで差し替え。

### 4. 積層

```
browser-use-clj      computer-use-clj          ← エージェント層
        └────────┬────────┘
          langgraph-clj          comfyui-clj   ← オーケストレーション層
   (graph/checkpoint/prebuilt)  (node-graph engine)
                └───────┬───────────┘
                  langchain-clj                ← このリポジトリ (基盤層)
  (runnable / message / prompt / model / tool / parser / memory / db)
```

下流は全て `io.github.com-junkawasaki/langchain-clj {:git/tag …}` の
git 依存(直接または langgraph-clj 経由の transitive)。

langgraph-clj v0.1 に同居していた langchain 層をこのリポジトリへ切り出した
(`langgraph.{runnable,message,prompt,model,tool,parser,memory,db}` →
`langchain.*`)。

## 非スコープ (v0.1)

- Datalog rules (%)・複数 db ソース・`d/history`
- トークン単位ストリーミング(チャンク列としての `stream` のみ)
- retriever / vector store / embeddings(必要になったら Datalog ビューとして設計)
