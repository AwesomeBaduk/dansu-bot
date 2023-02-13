(ns dansu-bot.persistence
  (:require ["fs" :as fs]))

(defn append-json-line [filename m]
  (.appendFileSync fs filename (str (.stringify js/JSON (clj->js m)) \newline)))

(defn record-tourney-participant [participant-info]
  (append-json-line "registered_users.njson" participant-info))

(defn record-game-result [game-info]
  (append-json-line "game_results.nsjon" game-info))
