(ns dansu-bot.ogs-api
  (:refer-clojure :exclude [parse-long parse-double])
  (:require [clojure.core.async :as a :refer [go <! chan put! close!]]
            ["https"            :refer [request]]))



(def ogs-api-host "online-go.com")

(defn ogs-options [entity-type id]
  (clj->js {:hostname ogs-api-host
            :port     443
            :path     (str "/api/v1/" (name entity-type) "/" id)
            :method   "GET"}))

(defn json->clj [s]
  (try
    (js->clj (.parse js/JSON s) :keywordize-keys true)
    (catch js/Error e
      (ex-info "API returned invalid JSON!"
               {:original-error e}))))

;; Mumble, grumble, frickin native node apis...
(defn get-ogs [entity-type id]
  (go
    (try
      (let [result (chan)
            body (atom "")
            req    (request
                    (ogs-options entity-type id)
                    (fn [^js res]

                      (.on res "data" #(swap! body str %))
                      (.on res "end" #(let [body (json->clj @body)]
                                        (if (= (.-statusCode res) 200)
                                          (put! result body)
                                          (put! result (ex-info "Error returned from ogs api"
                                                                {:server-response body})))
                                        (close! result)))))]
        (.on req "error"
             #(put! result (ex-info "Error calling ogs api"
                                    {:original-error %})))
        (.end req)
        (<! result))
      (catch js/Error e
        (ex-info "Unknown error during ogs api call:"
                 {:original-error e})))))

(defn get-player [player-id]
  (get-ogs :players player-id))

(defn get-game [game-id]
  (get-ogs :games game-id))

(def tp "1023373")

(def eg "44047682")
