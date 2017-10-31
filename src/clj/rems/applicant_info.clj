(ns rems.applicant-info
  (:require [rems.collapsible :as collapsible]
            [rems.context :as context]
            [rems.guide :refer :all]
            [rems.info-field :as info-field]
            [rems.roles :refer [when-roles]]
            [rems.text :refer :all]
            [rems.util :refer [get-user-id
                               get-username]]
            [rems.text :as text]))

(defn details [id user-attributes]
  (collapsible/component
   {:id id
    :title (str (text :t.applicant-info/applicant) ": " (get-username user-attributes))
    :collapse (when-roles #{:approver :reviewer}
                [:form
                 (for [[k v] user-attributes]
                   (info-field/component k v))])}))

(defn guide
  []
  (list
   (example "applicant-info for applicant shows no details"
            (binding [context/*roles* #{:applicant}]
              (details "info1" {"eppn" "developer@uu.id" "commonName" "Deve Loper"})))
   (example "applicant-info for approver shows attributes"
            (binding [context/*roles* #{:approver}]
              (details "info2" {"eppn" "developer@uu.id" "commonName" "Deve Loper"})))))
