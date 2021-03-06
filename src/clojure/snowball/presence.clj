(ns snowball.presence
  (:require [clojure.core.async :as a]
            [bounce.system :as b]
            [taoensso.timbre :as log]
            [snowball.config :as config]
            [snowball.discord :as discord]
            [snowball.speech :as speech]))

(defn allowed-to-join? [channel]
  (let [{:keys [whitelist blacklist]} (get config/value :presence)
        cid (discord/->id channel)]
    (cond
      (seq whitelist) (whitelist cid)
      (seq blacklist) (not (blacklist cid))
      :default true)))

(b/defcomponent poller {:bounce/deps #{discord/client config/value speech/synthesiser}}
  (log/info "Starting presence poller")
  (let [closed?! (atom false)]
    (-> (a/go-loop []
          (when-not @closed?!
            (try
              (a/<! (a/timeout (get-in config/value [:presence :poll-ms])))
              (let [desired-channel (->> (discord/channels)
                                         (sequence
                                           (comp
                                             (filter allowed-to-join?)
                                             (filter discord/has-speaking-users?)))
                                         (first))
                    current-channel (discord/current-channel)]
                (cond
                  (and current-channel (nil? desired-channel))
                  (discord/leave! current-channel)

                  (and (or (nil? current-channel)
                           (not (discord/has-speaking-users? current-channel)))
                       desired-channel)
                  (do
                    (discord/join! desired-channel)

                    ;; This hack gets around a bug in Discord's API.
                    ;; You need to send some audio to start receiving audio.
                    (speech/say! ""))))
              (catch Exception e
                (log/error "Caught an error in presence loop" e)))
            (recur)))
        (b/with-stop
          (log/info "Stopping presence poller")
          (reset! closed?! true)))))
