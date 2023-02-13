(ns dansu-bot.core
  (:require [dansu-bot.discord-wrapper :as discord
                                       :refer [slash-command subcommand handle-command
                                               start]]
            [dansu-bot.ogs-api         :refer [get-player get-game]]
            [dansu-bot.persistence     :as persistence]
            [clojure.core.async        :refer [go]]
            [async-error.core          :refer-macros [<?]]
            [clojure.string            :as str]))


(def s (slash-command :tournament "Play in the Awesome Baduk Tournament!"
                      :subcommands [(subcommand :register "Sign up to play"
                                                :options [[:string :ogs-player "The link to your OGS profile (https://online-go.com/player/<some-id>" :required? true]])
                                    (subcommand :result "Report a game result"
                                                :options [[:string :game-id "The link to your game on OGS (https://online-go.com/game/<some-id>" :required? true]])]))

(defn update-comms! []
  (discord/register-commands [s]))

(defn convert-ogs-rank [rating]
  (let [ranking (.floor js/Math rating)]
    (if (>= ranking 30)
      (str (- ranking 29) "d")
      (str (- 30 ranking) "k"))))


(defmethod handle-command [:tournament :register] [_ ^js interaction]
  (go
    (try
      (let [player-id         (-> interaction .-options (.getString "ogs-player") (str/split #"/") last)
            ogs-result        (<? (get-player player-id))
            {:keys [username
                    ranking]} ogs-result
            rank-string       (convert-ogs-rank ranking)
            discord-id        (-> interaction .-user .-id)
            discord-name      (-> interaction .-user .-username)
            participant-info  {:ogs-name     username
                               :ranking      rank-string
                               :discord-id   discord-id
                               :discord-name discord-name}]
        (persistence/record-tourney-participant participant-info)
        (.reply interaction (str "Registered player " username "(" rank-string ")")))
      (catch ExceptionInfo e
        (.reply interaction (str "Error registering player: " (ex-message e) ", additional info: " (ex-data e))))
      (catch js/Error e
        (.reply interaction (str "Unknown error registering player: " e))))))

(defmethod handle-command [:tournament :result] [_ ^js interaction]
  (go
    (try
      (let [game-id              (-> interaction .-options (.getString "game-id") (str/split #"/") last)
            ogs-result           (<? (get-game game-id))
            {:keys [players
                    black_lost]} ogs-result
            white                (-> players :white :username)
            black                (-> players :black :username)
            winner               (if black_lost white black)
            game-info            {:game-id game-id
                                  :white   white
                                  :black   black
                                  :winner  winner}]
        (persistence/record-game-result game-info)
        (.reply interaction
                (str "Result recorded! "
                     winner
                     " won!")))
      (catch ExceptionInfo e
        (.reply interaction (str "Error recording tournament results: " (ex-message e) ", additional info: " (ex-data e))))
      (catch js/Error e
        (.reply interaction (str "Unknown error while reporting result: " e))))))

(defn main [& cli-args]
  (start))
