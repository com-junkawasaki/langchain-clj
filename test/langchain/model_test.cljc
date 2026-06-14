(ns langchain.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [langchain.model :as model]
            [langchain.tool :as tool]))

;; json host capabilities for tests — EDN stands in for JSON
(def jw pr-str)
(def jr #?(:clj read-string :cljs cljs.reader/read-string))

(def computer-tool
  {:name "computer" :description "Control a computer."
   :schema {:type "object"
            :properties {:action {:type "string"}}
            :required ["action"]}
   :fn (fn [_] "ok")})

(deftest tool->openai-wire-format
  (is (= {:type "function"
          :function {:name "computer"
                     :description "Control a computer."
                     :parameters (:schema computer-tool)}}
         (tool/->openai computer-tool))))

(deftest tool-execute-preserves-content-blocks
  (testing "string results pass through"
    (is (= "ok" (:content (tool/execute [computer-tool]
                                        {:id "1" :name "computer" :input {}})))))
  (testing "content-block vectors (e.g. screenshots) are NOT stringified"
    (let [shot [{:type "image" :source {:type "base64"
                                        :media_type "image/png" :data "AAAA"}}]
          t {:name "shot" :description "" :schema {} :fn (fn [_] shot)}]
      (is (= shot (:content (tool/execute [t] {:id "1" :name "shot" :input {}}))))))
  (testing "other results stringify"
    (let [t {:name "n" :description "" :schema {} :fn (fn [_] 42)}]
      (is (= "42" (:content (tool/execute [t] {:id "1" :name "n" :input {}})))))))

(deftest openai-request-body-mapping
  (let [messages [{:role :system :content "be careful"}
                  {:role :user :content "click the button"}
                  {:role :assistant :content ""
                   :tool-calls [{:id "c1" :name "computer"
                                 :input {:action "left_click"}}]}
                  {:role :tool :tool-call-id "c1" :content "clicked"}]
        body (model/openai-request-body jw messages
                                        {:model "gemma" :tools [computer-tool]})]
    (testing "roles map 1:1, system stays a message"
      (is (= ["system" "user" "assistant" "tool"]
             (mapv :role (:messages body)))))
    (testing "tool calls carry json-encoded arguments"
      (is (= {:id "c1" :type "function"
              :function {:name "computer" :arguments (jw {:action "left_click"})}}
             (-> body :messages (nth 2) :tool_calls first))))
    (testing "tool result references the call id"
      (is (= {:role "tool" :tool_call_id "c1" :content "clicked"}
             (-> body :messages (nth 3)))))
    (testing "tools use the function wire format"
      (is (= [(tool/->openai computer-tool)] (:tools body))))))

(deftest openai-request-body-image-tool-result
  (let [shot [{:type "text" :text "Screen 1280x800"}
              {:type "image" :source {:type "base64"
                                      :media_type "image/png" :data "AAAA"}}]
        messages [{:role :user :content "look"}
                  {:role :assistant :content ""
                   :tool-calls [{:id "c1" :name "computer"
                                 :input {:action "screenshot"}}]}
                  {:role :tool :tool-call-id "c1" :content shot}]
        msgs (:messages (model/openai-request-body jw messages {:model "m"}))]
    (testing "image tool result splits into tool text + user image message"
      (is (= ["user" "assistant" "tool" "user"] (mapv :role msgs)))
      (is (= "Screen 1280x800 (screenshot in the next user message)"
             (:content (nth msgs 2))))
      (is (= [{:type "image_url"
               :image_url {:url "data:image/png;base64,AAAA"}}]
             (:content (nth msgs 3)))))))

(deftest openai-parse-response-tool-calls
  (let [resp {:choices
              [{:message {:content ""
                          :tool_calls [{:id "x1"
                                        :function {:name "computer"
                                                   :arguments (jw {:action "screenshot"})}}]}
                :finish_reason "tool_calls"}]
              :usage {:total_tokens 42}}
        msg (model/openai-parse-response jr resp)]
    (is (= :assistant (:role msg)))
    (is (= "tool_calls" (:stop-reason msg)))
    (is (= [{:id "x1" :name "computer" :input {:action "screenshot"}}]
           (:tool-calls msg)))
    (is (= {:total_tokens 42} (:usage msg))))
  (testing "missing tool-call ids get a stable fallback"
    (is (= "call_0"
           (-> (model/openai-parse-response
                jr {:choices [{:message {:tool_calls
                                         [{:function {:name "f" :arguments "{}"}}]}}]})
               :tool-calls first :id))))
  (testing "plain text responses have no :tool-calls"
    (let [msg (model/openai-parse-response
               jr {:choices [{:message {:content "hi"} :finish_reason "stop"}]})]
      (is (= "hi" (:content msg)))
      (is (nil? (:tool-calls msg))))))

(deftest openai-model-requires-host-capabilities
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (model/openai-model {:model "m"})))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (model/openai-model {:http-fn identity
                                    :json-write jw :json-read jr}))))
