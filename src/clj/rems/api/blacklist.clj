(ns rems.api.blacklist
  (:require [clj-time.core :as time]
            [clojure.tools.logging :as log]
            [compojure.api.sweet :refer :all]
            [rems.api.schema :as schema]
            [rems.api.services.command :as command]
            [rems.api.services.blacklist :as blacklist]
            [rems.application.rejecter-bot :as rejecter-bot]
            [rems.db.users :as users]
            [rems.util :refer [getx-user-id]]
            [ring.util.http-response :refer [ok]]
            [schema.core :as s])
  (:import [org.joda.time DateTime]))

(s/defschema BlacklistCommand
  {:blacklist/resource {:resource/ext-id s/Str}
   :blacklist/user {:userid schema/UserId}
   :comment s/Str})

(s/defschema BlacklistEntryWithDetails
  (assoc schema/BlacklistEntry
         :blacklist/comment s/Str
         :blacklist/added-by schema/UserWithAttributes
         :blacklist/added-at DateTime))

(defn- format-blacklist-entry [entry]
  {:blacklist/resource {:resource/ext-id (:resource/ext-id entry)}
   :blacklist/user (users/get-user (:userid entry))
   :blacklist/added-at (:event/time entry)
   :blacklist/comment (:event/comment entry)
   :blacklist/added-by (users/get-user (:event/actor entry))})

(def blacklist-api
  (context "/blacklist" []
    :tags ["blacklist"]

    (GET "/" []
      :summary "Get blacklist entries"
      :roles #{:handler :owner :reporter :organization-owner}
      :query-params [{user :- schema/UserId nil}
                     {resource :- s/Str nil}]
      :return [BlacklistEntryWithDetails]
      (->> (blacklist/get-blacklist {:userid user
                                     :resource/ext-id resource})
           (mapv format-blacklist-entry)
           (ok)))

    (GET "/users" []
      :summary "Existing REMS users available for adding to the blacklist"
      ;; TODO reporter shouldn't strictly need this, but it's read-only so no harm.
      :roles #{:owner :handler :reporter :organization-owner}
      :return [schema/UserWithAttributes]
      (ok (users/get-users)))

    ;; TODO write access to blacklist for organization-owner

    (POST "/add" []
      :summary "Add a blacklist entry"
      :roles #{:owner :handler}
      :body [command BlacklistCommand]
      :return schema/SuccessResponse
      (blacklist/add-user-to-blacklist! (getx-user-id) command)
      (doseq [cmd (rejecter-bot/reject-all-applications-by (get-in command [:blacklist/user :userid]))]
        (let [result (command/command! cmd)]
          (when (:errors result)
            (log/error "Failure when running rejecter bot commands:" {:cmd cmd :result result}))))

      (ok {:success true}))

    (POST "/remove" []
      :summary "Remove a blacklist entry"
      :roles #{:owner :handler}
      :body [command BlacklistCommand]
      :return schema/SuccessResponse
      (blacklist/remove-user-from-blacklist! (getx-user-id) command)
      (ok {:success true}))))
