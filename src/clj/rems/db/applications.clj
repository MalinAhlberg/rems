(ns rems.db.applications
  "Query functions for forms and applications."
  (:require [clojure.core.cache :as cache]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [conman.core :as conman]
            [medley.core :refer [map-vals]]
            [mount.core :as mount]
            [rems.application.approver-bot :as approver-bot]
            [rems.application.commands :as commands]
            [rems.application.events-cache :as events-cache]
            [rems.application.model :as model]
            [rems.auth.util :refer [throw-forbidden]]
            [rems.db.attachments :as attachments]
            [rems.db.blacklist :as blacklist]
            [rems.db.catalogue :as catalogue]
            [rems.db.core :as db]
            [rems.db.events :as events]
            [rems.db.form :as form]
            [rems.db.licenses :as licenses]
            [rems.db.users :as users]
            [rems.db.workflow :as workflow]
            [rems.form-validation :as form-validation]
            [rems.permissions :as permissions]
            [rems.scheduler :as scheduler]
            [rems.util :refer [atom? getx secure-token]])
  (:import [org.joda.time Duration]))

;;; Creating applications

(defn- allocate-external-id! [prefix]
  (conman/with-transaction [rems.db.core/*db* {:isolation :serializable}]
    (let [all (db/get-external-ids {:prefix prefix})
          last (apply max (cons 0 (map (comp read-string :suffix) all)))
          new (str (inc last))]
      (db/add-external-id! {:prefix prefix :suffix new})
      {:prefix prefix :suffix new})))

(defn- format-external-id [{:keys [prefix suffix]}]
  (str prefix "/" suffix))

(defn- application-external-id! [time]
  (let [id-prefix (str (.getYear time))]
    (format-external-id (allocate-external-id! id-prefix))))

(defn- allocate-application-ids! [time]
  {:application/id (:id (db/create-application!))
   :application/external-id (application-external-id! time)})

;;; Running commands

(defn get-catalogue-item-licenses [catalogue-item-id]
  (db/get-licenses
   {:wfid (:wfid (catalogue/get-localized-catalogue-item catalogue-item-id))
    :items [catalogue-item-id]}))

(def ^:private command-injections
  {:valid-user? users/user-exists?
   :validate-fields form-validation/validate-fields
   :secure-token secure-token
   :get-catalogue-item catalogue/get-localized-catalogue-item
   :get-catalogue-item-licenses get-catalogue-item-licenses
   :get-workflow workflow/get-workflow
   :allocate-application-ids! allocate-application-ids!
   :add-to-blacklist! blacklist/add-to-blacklist!
   :blacklisted? blacklist/blacklisted?})

(declare get-unrestricted-application)

(defn run-process-managers [new-events]
  ;; the copy-as-new command produces events for multiple applications, so there can be 1 or 2 app-ids
  (let [app-ids (->> new-events
                     (filter (fn [event]
                               ;; performance optimization: run only when an interesting event happens
                               ;; (reading the app from DB is slowish; consider an in-memory event-based solution instead)
                               (= :application.event/submitted (:event/type event))))
                     (map :application/id)
                     distinct)]
    (->> app-ids
         (map get-unrestricted-application)
         (mapcat #(approver-bot/generate-commands % command-injections))
         doall)))

(defn command! [cmd]
  ;; Use locks to prevent multiple commands being executed in parallel.
  ;; Serializable isolation level will already avoid anomalies, but produces
  ;; lots of transaction conflicts when there is contention. This lock
  ;; roughly doubles the throughput for rems.db.test-transactions tests.
  (jdbc/execute! db/*db* ["LOCK TABLE application_event IN SHARE ROW EXCLUSIVE MODE"])
  (let [app (when-let [app-id (:application-id cmd)]
              (get-unrestricted-application app-id))
        result (commands/handle-command cmd app command-injections)]
    (when-not (:errors result)
      (doseq [event (:events result)]
        (events/add-event! event))
      (doseq [cmd (run-process-managers (:events result))]
        (command! cmd)))
    result))

(defn get-application-by-invitation-token [invitation-token]
  (:id (db/get-application-by-invitation-token {:token invitation-token})))

;;; Fetching applications (for API) (incl. caching)

(def ^:private fetcher-injections
  {:get-attachments-for-application attachments/get-attachments-for-application
   :get-form-template form/get-form-template
   :get-catalogue-item catalogue/get-localized-catalogue-item
   :get-license licenses/get-license
   :get-user users/get-user
   :get-users-with-role users/get-users-with-role
   :get-workflow workflow/get-workflow
   :blacklisted? blacklist/blacklisted?})

;; short-lived cache to speed up pollers which get the application
;; repeatedly for each event instead of building their own projection
(mount/defstate application-cache
  :start (atom (cache/ttl-cache-factory {} :ttl 10000)))

;; TODO combine with reload-cache!?
(defn- reset-application-cache! []
  (swap! application-cache empty))

(defn get-unrestricted-application
  "Returns the full application state without any user permission
   checks and filtering of sensitive information. Don't expose via APIs."
  [application-id]
  (let [events (events/get-application-events application-id)
        cache-key [application-id (count events)]
        build-app (fn [_] (model/build-application-view events fetcher-injections))]
    (if (empty? events)
      nil ; application not found
      ;; TODO: this caching could be removed by refactoring the pollers to build their own projection
      (if (atom? application-cache) ; guard against not started cache
        (-> (swap! application-cache cache/through-cache cache-key build-app)
            (getx cache-key))
        (build-app nil)))))

(defn get-application
  "Returns the part of application state which the specified user
   is allowed to see. Suitable for returning from public APIs as-is."
  [user-id application-id]
  (when-let [application (get-unrestricted-application application-id)]
    (or (model/apply-user-permissions application user-id)
        (throw-forbidden))))

;;; Listing all applications

(defn- all-applications-view
  "Projection for the current state of all applications."
  [applications event]
  (if-let [app-id (:application/id event)] ; old style events don't have :application/id
    (update applications app-id model/application-view event)
    applications))

(defn- exclude-unnecessary-keys-from-overview [application]
  (dissoc application
          :application/events
          :application/form
          :application/licenses))

(mount/defstate
  ^{:doc "The cached state will contain the following keys:
          ::raw-apps
          - Map from application ID to the pure projected state of an application.
          ::enriched-apps
          - Map from application ID to the enriched version of an application.
            Built from the raw apps by calling `enrich-with-injections`.
            Since the injected entities (resources, forms etc.) are mutable,
            it creates a cache invalidation problem here.
          ::apps-by-user
          - Map from user ID to a list of applications which the user can see.
            Built from the enriched apps by calling `apply-user-permissions`.
          ::roles-by-user
          - Map from user ID to a set of all application roles which the user has,
            a union of roles from all applications."}
  all-applications-cache
  :start (events-cache/new))

(defn- group-apps-by-user [apps]
  (->> apps
       (mapcat (fn [app]
                 (for [user (keys (::permissions/user-roles app))]
                   (when-let [app (model/apply-user-permissions app user)]
                     [user app]))))
       (reduce (fn [apps-by-user [user app]]
                 (update apps-by-user user conj app))
               {})))

(deftest test-group-apps-by-user
  (let [apps [(-> {:application/id 1}
                  (permissions/give-role-to-users :foo ["user-1" "user-2"]))
              (-> {:application/id 2}
                  (permissions/give-role-to-users :bar ["user-1"]))]]
    (is (= {"user-1" [{:application/id 1} {:application/id 2}]
            "user-2" [{:application/id 1}]}
           (->> (group-apps-by-user apps)
                (map-vals (fn [apps]
                            (->> apps
                                 (map #(select-keys % [:application/id]))
                                 (sort-by :application/id)))))))))

(defn- group-roles-by-user [apps]
  (->> apps
       (mapcat (fn [app] (::permissions/user-roles app)))
       (reduce (fn [roles-by-user [user roles]]
                 (update roles-by-user user set/union roles))
               {})))

(deftest test-group-roles-by-user
  (let [apps [(-> {:application/id 1}
                  (permissions/give-role-to-users :foo ["user-1" "user-2"]))
              (-> {:application/id 2}
                  (permissions/give-role-to-users :bar ["user-1"]))]]
    (is (= {"user-1" #{:foo :bar}
            "user-2" #{:foo}}
           (group-roles-by-user apps)))))

(defn refresh-all-applications-cache! []
  (events-cache/refresh!
   all-applications-cache
   (fn [state events]
     ;; Because enrich-with-injections is not idempotent,
     ;; it's necessary to hold on to the "raw" applications.
     ;; TODO: consider making enrich-with-injections idempotent (move dissocs to hide-non-public-information and other small refactorings)
     (let [raw-apps (reduce all-applications-view (::raw-apps state) events)
           updated-app-ids (distinct (map :application/id events))
           ;; TODO: batched injections: only one DB query to fetch all catalogue items etc.
           ;;       - fetch all items in the background as a batch, use plain maps as injections
           ;;       - change db/get-license and db/get-user-attributes to fetch all rows if ID is not defined
           cached-injections (map-vals memoize fetcher-injections)
           enriched-apps (->> (select-keys raw-apps updated-app-ids)
                              (map-vals #(model/enrich-with-injections % cached-injections))
                              (merge (::enriched-apps state)))]
       {::raw-apps raw-apps
        ::enriched-apps enriched-apps
        ::apps-by-user (group-apps-by-user (vals enriched-apps))
        ::roles-by-user (group-roles-by-user (vals enriched-apps))}))))

(defn get-all-unrestricted-applications []
  (-> (refresh-all-applications-cache!)
      ::enriched-apps
      (vals)))

(defn get-all-applications [user-id]
  (-> (refresh-all-applications-cache!)
      (get-in [::apps-by-user user-id])
      (->> (map exclude-unnecessary-keys-from-overview))))

(defn get-all-application-roles [user-id]
  (-> (refresh-all-applications-cache!)
      (get-in [::roles-by-user user-id])
      (set)))

(defn- my-application? [application]
  (some #{:applicant :member} (:application/roles application)))

(defn get-my-applications [user-id]
  (->> (get-all-applications user-id)
       (filter my-application?)))

(defn reload-cache! []
  ;; TODO: Here is a small chance that a user will experience a cache miss. Consider rebuilding the cache asynchronously and then `reset!` the cache.
  (reset-application-cache!)
  (events-cache/empty! all-applications-cache)
  (refresh-all-applications-cache!))

;; empty the cache occasionally in case some of the injected entities are changed
(mount/defstate all-applications-cache-reloader
  :start (scheduler/start! reload-cache! (Duration/standardHours 1))
  :stop (scheduler/stop! all-applications-cache-reloader))
