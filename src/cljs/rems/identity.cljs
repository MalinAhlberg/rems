(ns rems.identity
  (:require [re-frame.core :as rf]))

;;; subscriptions

(rf/reg-sub
 :identity
 (fn [db _]
   (:identity db)))

(rf/reg-sub
 :user
 (fn [db _]
   (get-in db [:identity :user])))

(rf/reg-sub
 :roles
 (fn [db _]
   (get-in db [:identity :roles])))

;;; handlers

(rf/reg-event-db
 :set-identity
 (fn [db [_ identity]]
   (assoc db :identity identity)))

(rf/reg-event-db
 :set-roles
 (fn [db [_ roles]]
   (assoc-in db [:identity :roles] roles)))

(defn set-identity!
  "Receives as a parameter following kind of structure:
   {:user {:eppn \"\"eppn\" \"developer\"
           :email \"developer@e.mail\"
           :displayName \"deve\"
           :surname \"loper\"
           ...}
    :roles [\"applicant\" \"approver\"]}
    Roles are converted to clojure keywords inside the function before dispatching"
  [user-and-roles]
  (let [user-and-roles (js->clj user-and-roles :keywordize-keys true)]
    (rf/dispatch-sync [:set-identity (if (:user user-and-roles)
                                       (assoc user-and-roles :roles (set (map keyword (:roles user-and-roles))))
                                       user-and-roles)])))

(defn set-roles! [roles]
  (rf/dispatch-sync [:set-roles (set (map keyword roles))]))