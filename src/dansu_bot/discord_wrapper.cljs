(ns dansu-bot.discord-wrapper
  (:require ["discord.js"            :as discord]
            ["fs"                    :as fs]
            ["@discordjs/builders"   :refer [SlashCommandBuilder]]
            ["@discordjs/rest"       :refer [REST]]
            ["discord-api-types/v10" :refer [Routes PermissionFlagsBits]]
            [clojure.edn             :as edn]
            [clojure.core.async      :refer [put! promise-chan go <!]]))


(defn promise->chan
  [promise & {:as err-info}]
  (let [ch (promise-chan)]
    (-> promise
        (.then #(put! ch %))
        (.catch #(put! ch (ex-info "Error in js promise" (merge err-info
                                                                {:error %})))))
    ch))

(defn set-name-desc [^js thing name-key description]
  (-> thing
      (.setName (name name-key))
      (.setDescription description)))

(defn make-option [[_ option-key description & {:keys [required?]}]]
  (fn [^js option]
    (let [basic-option (set-name-desc option option-key description)]
      (cond-> basic-option
        required? (.setRequired true)))))

(defmulti add-option (fn [_ option-info] (first option-info)))

(defmethod add-option :string [builder option-info]
  (.addStringOption ^js builder (make-option option-info)))

(defn add-options [builder options]
  (reduce (fn [current option-info]
            (add-option current option-info))
          builder
          options))

(defn subcommand [command-key description & {:keys [options]}]
  (fn [subcommand]
    (let [basic-subcommand (set-name-desc subcommand command-key description)]
      (cond-> basic-subcommand
        (not-empty options) (add-options options)))))

(defn add-subcommands [builder subcommands]
  (reduce (fn [current subcommand]
            (.addSubcommand ^js current subcommand))
          builder
          subcommands))

(defn slash-command [command-key description & {:keys [subcommands options admin-only?]}]
  (let [basic-command (set-name-desc (SlashCommandBuilder.) command-key description)]
    (-> (cond-> basic-command
          (not-empty options) (add-options options)
          (not-empty subcommands) (add-subcommands subcommands)
          admin-only? (.setDefaultMemberPermissions (bit-or (.-BanMembers PermissionFlagsBits)
                                                            (.-KickMembers PermissionFlagsBits))))
        .toJSON
        (js->clj :keywordize-keys true))))

(defn read-config []
  (edn/read-string (fs/readFileSync "resources/client-config.edn" "utf-8")))

;:; Helpers

(defn calling-user [interaction]
  (-> ^js interaction .-user .-username))

;; Just applicationCommands without the guild id

(def guild-route
  (let [{:keys [application-id guild-id]} (read-config)]
    (.applicationGuildCommands Routes
                               application-id
                               guild-id)))

(defn register-commands [commands]
  (let [{:keys [bot-token]} (read-config)
        rest                (.setToken (REST. #js {:version "10"}) bot-token)]
    (go
      (->> (.put rest
                 guild-route
                 (clj->js {:body commands}))
           promise->chan
           <!
           print))))

(defonce client-atom (atom nil))

(defmulti handle-command (fn [command-path _interaction] command-path))

(defn ^:dev/before-load stop []
  (when-let [client @client-atom]
    (.destroy ^js client)
    (reset! client-atom nil)))

(defn ^:dev/after-load start []
  (let [client (discord/Client. #js { "intents" (-> discord/Intents .-FLAGS .-GUILDS)})]
    (.once client "ready" #(print "Ready!"))
    (.on client "interactionCreate"
         (fn [^js interaction]
           (when (.isCommand interaction)
             (let [command-path (->> [(.-commandName interaction) (-> interaction .-options .getSubcommand)]
                                     (remove nil?)
                                     (mapv keyword))]
               (try (handle-command command-path interaction)
                    (catch js/Error e
                      (.reply interaction (str "Unknown error handling command: " e))))))))
    (.login client (:bot-token (read-config)))
    (reset! client-atom client)))


; (.reply interaction "Pong!")
