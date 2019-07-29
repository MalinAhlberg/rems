(ns ^:integration rems.db.test-entitlements
  (:require [cheshire.core :as cheshire]
            [clj-time.core :as time]
            [clojure.test :refer :all]
            [rems.db.applications :as applications]
            [rems.db.core :as db]
            [rems.db.entitlements :as entitlements]
            [rems.db.test-data :as test-data]
            [rems.db.testing :refer [test-db-fixture rollback-db-fixture test-data-fixture]]
            [rems.poller.entitlements :as entitlements-poller]
            [rems.testing-util :refer [suppress-logging]]
            [stub-http.core :as stub]))

(use-fixtures
  :once
  (suppress-logging "rems.db.entitlements")
  test-db-fixture
  rollback-db-fixture
  test-data-fixture)

(def +entitlements+
  [{:resid "res1" :catappid 11 :userid "user1" :start (time/date-time 2001 10 11) :mail "user1@tes.t"}
   {:resid "res2" :catappid 12 :userid "user2" :start (time/date-time 2002 10 11) :mail "user2@tes.t"}])

(def +expected-payload+
  [{"resource" "res1" "application" 11 "user" "user1" "mail" "user1@tes.t"}
   {"resource" "res2" "application" 12 "user" "user2" "mail" "user2@tes.t"}])

(defn run-with-server
  "Run callback with a mock entitlements http server set up.
   Return sequence of data received by mock server."
  [endpoint-spec callback]
  (with-open [server (stub/start! {"/entitlements" endpoint-spec})]
    (with-redefs [rems.config/env {:entitlements-target
                                   {:add (str (:uri server) "/entitlements")}}]
      (callback)
      (for [r (stub/recorded-requests server)]
        (cheshire/parse-string (get-in r [:body "postData"]))))))

(deftest test-post-entitlements
  (let [log (atom [])]
    (with-redefs [db/log-entitlement-post! #(swap! log conj %)]
      (testing "ok"
        (is (= [+expected-payload+]
               (run-with-server {:status 200}
                                #(#'entitlements/post-entitlements :add +entitlements+))))
        (let [[{payload :payload status :status}] @log]
          (is (= 200 status))
          (is (= +expected-payload+ (cheshire/parse-string payload))))
        (reset! log []))
      (testing "not found"
        (run-with-server {:status 404}
                         #(#'entitlements/post-entitlements :add +entitlements+))
        (let [[{payload :payload status :status}] @log]
          (is (= 404 status))
          (is (= +expected-payload+ (cheshire/parse-string payload))))
        (reset! log []))
      (testing "timeout"
        (run-with-server {:status 200 :delay 5000} ;; timeout of 2500 in code
                         #(#'entitlements/post-entitlements :add +entitlements+))
        (let [[{payload :payload status :status}] @log]
          (is (= "exception" status))
          (is (= +expected-payload+ (cheshire/parse-string payload)))))
      (testing "no server"
        (with-redefs [rems.config/env {:entitlements-target "http://invalid/entitlements"}]
          (#'entitlements/post-entitlements :add +entitlements+)
          (let [[{payload :payload status :status}] @log]
            (is (= "exception" status))
            (is (= +expected-payload+ (cheshire/parse-string payload)))))))))

(defmacro with-stub-server [sym & body]
  `(let [~sym (stub/start! {"/add" {:status 200}
                            "/remove" {:status 200}})]
     (with-redefs [rems.config/env {:entitlements-target
                                    {:add (str (:uri ~sym) "/add")
                                     :remove (str (:uri ~sym) "/remove")}}]
       ~@body)))

(deftest test-entitlement-granting
  (entitlements-poller/run) ; clear previous entitlements
  (let [applicant "bob"
        member "elsa"
        admin "owner"
        wfid (test-data/create-dynamic-workflow! {:handlers [admin]})
        form-id (test-data/create-form! {})
        lic-id1 (test-data/create-license! {})
        lic-id2 (test-data/create-license! {})
        item1 (test-data/create-catalogue-item!
               {:resource-id (test-data/create-resource! {:resource-ext-id "resource1"
                                                          :license-ids [lic-id1]})
                :form-id form-id
                :workflow-id wfid})
        item2 (test-data/create-catalogue-item!
               {:resource-id (test-data/create-resource! {:resource-ext-id "resource2"
                                                          :license-ids [lic-id2]})
                :form-id form-id
                :workflow-id wfid})
        item3 (test-data/create-catalogue-item!
               {:resource-id (test-data/create-resource! {:resource-ext-id "resource3"
                                                          :license-ids [lic-id1]})
                :form-id form-id
                :workflow-id wfid})]
    (db/add-user! {:user applicant :userattrs (cheshire/generate-string {"mail" "b@o.b"})})
    (db/add-user! {:user member :userattrs (cheshire/generate-string {"mail" "e.l@s.a"})})
    (db/add-user! {:user admin :userattrs nil})

    (let [app-id (test-data/create-application! {:actor applicant :catalogue-item-ids [item1 item2]})]
      (testing "submitted application should not yet cause entitlements"
        (with-stub-server server
          (test-data/run! {:type :application.command/submit
                           :actor applicant
                           :application-id app-id
                           :time (time/now)})
          (test-data/run! {:type :application.command/add-member
                           :actor admin
                           :application-id app-id
                           :member {:userid member}
                           :time (time/now)})
          (entitlements-poller/run)

          (is (empty? (db/get-entitlements {:application app-id})))
          (is (empty? (stub/recorded-requests server))))

        (testing "approved application should not yet cause entitlements"
          (with-stub-server server
            (test-data/run! {:type :application.command/approve
                             :actor admin
                             :application-id app-id
                             :comment ""
                             :time (time/now)})
            (entitlements-poller/run)

            (is (empty? (db/get-entitlements {:application app-id})))
            (is (empty? (stub/recorded-requests server)))))

        (testing "approved application, licenses accepted by one user generates entitlements for that user"
          (with-stub-server server
            (test-data/run! {:type :application.command/accept-licenses
                             :actor applicant
                             :application-id app-id
                             :accepted-licenses [lic-id1 lic-id2]
                             :time (time/now)})

            (test-data/run! {:type :application.command/accept-licenses
                             :actor member
                             :application-id app-id
                             :accepted-licenses [lic-id1] ; only accept some licenses
                             :time (time/now)})

            (is (= {applicant #{lic-id1 lic-id2}
                    member #{lic-id1}}
                   (:application/accepted-licenses (applications/get-unrestricted-application app-id))))

            (entitlements-poller/run)
            (entitlements-poller/run) ;; run twice to check idempotence
            (is (= 2 (count (stub/recorded-requests server))))
            (testing "db"
              (is (= [[applicant "resource1"] [applicant "resource2"]]
                     (map (juxt :userid :resid) (db/get-entitlements {:application app-id})))))
            (testing "POST"
              (let [data (take 2 (stub/recorded-requests server))
                    targets (map :path data)
                    bodies (->> data
                                (map #(get-in % [:body "postData"]))
                                (map #(cheshire/parse-string % keyword))
                                (apply concat))]
                (is (every? #{"/add"} targets))
                (is (= [{:resource "resource1" :application app-id :user "bob" :mail "b@o.b"}
                        {:resource "resource2" :application app-id :user "bob" :mail "b@o.b"}]
                       bodies)))))))

      (testing "approved application, more accepted licenses generates more entitlements"
        (with-stub-server server
          (test-data/run! {:type :application.command/accept-licenses
                           :actor member
                           :application-id app-id
                           :accepted-licenses [lic-id1 lic-id2] ; now accept all licenses
                           :time (time/now)})
          (entitlements-poller/run)

          (is (= 2 (count (stub/recorded-requests server))))
          (testing "db"
            (is (= [[applicant "resource1"] [applicant "resource2"]
                    [member "resource1"] [member "resource2"]]
                   (map (juxt :userid :resid) (db/get-entitlements {:application app-id})))))
          (testing "POST"
            (let [data (stub/recorded-requests server)
                  targets (map :path data)
                  bodies (->> data
                              (map #(get-in % [:body "postData"]))
                              (map #(cheshire/parse-string % keyword))
                              (apply concat))]
              (is (every? #{"/add"} targets))
              (is (= [{:resource "resource1" :application app-id :user "elsa" :mail "e.l@s.a"}
                      {:resource "resource2" :application app-id :user "elsa" :mail "e.l@s.a"}]
                     bodies))))))

      (testing "removing a member ends entitlements"
        (with-stub-server server
          (test-data/run! {:type :application.command/remove-member
                           :actor admin
                           :application-id app-id
                           :member {:userid member}
                           :comment "Left team"
                           :time (time/now)})
          (entitlements-poller/run)

          (is (= 2 (count (stub/recorded-requests server))))
          (testing "db"
            (is (= [[applicant "resource1"] [applicant "resource2"]]
                   (map (juxt :userid :resid) (db/get-entitlements {:application app-id :is-active? true})))))
          (testing "POST"
            (let [data (stub/recorded-requests server)
                  targets (map :path data)
                  bodies (->> data
                              (map #(get-in % [:body "postData"]))
                              (map #(cheshire/parse-string % keyword))
                              (apply concat))]
              (is (every? #{"/remove"} targets))
              (is (= [{:resource "resource1" :application app-id :user "elsa" :mail "e.l@s.a"}
                      {:resource "resource2" :application app-id :user "elsa" :mail "e.l@s.a"}]
                     bodies))))))

      (testing "changing resources changes entitlements"
        (with-stub-server server
          (test-data/run! {:type :application.command/change-resources
                           :actor admin
                           :application-id app-id
                           :catalogue-item-ids [item1 item3]
                           :comment "Removed second resource, added third resource"
                           :time (time/now)})
          (entitlements-poller/run)

          (is (= 2 (count (stub/recorded-requests server))))
          (testing "db"
            (is (= [[applicant "resource1"] [applicant "resource3"]]
                   (map (juxt :userid :resid) (db/get-entitlements {:application app-id :is-active? true})))))
          (testing "POST"
            (let [data (stub/recorded-requests server)
                  data (map #(assoc % :body (cheshire/parse-string (get-in % [:body "postData"]) keyword)) data)]
              (is (= [{:path "/add" :body [{:resource "resource3" :application app-id :user "bob" :mail "b@o.b"}]}
                      {:path "/remove" :body [{:resource "resource2" :application app-id :user "bob" :mail "b@o.b"}]}]
                     (map #(select-keys % [:path :body]) data)))))))

      (testing "closed application should end entitlements"
        (with-stub-server server
          (test-data/run! {:type :application.command/close
                           :actor admin
                           :application-id app-id
                           :comment "Finished"
                           :time (time/now)})
          (entitlements-poller/run)

          (is (= 2 (count (stub/recorded-requests server))))
          (testing "db"
            (is (= [] (db/get-entitlements {:application app-id :is-active? true}))))
          (testing "POST"
            (let [data (stub/recorded-requests server)
                  data (map #(assoc % :body (cheshire/parse-string (get-in % [:body "postData"]) keyword)) data)]
              (is (= [{:path "/remove" :body [{:resource "resource1" :application app-id :user "bob" :mail "b@o.b"}]}
                      {:path "/remove" :body [{:resource "resource3" :application app-id :user "bob" :mail "b@o.b"}]}]
                     (map #(select-keys % [:path :body]) data))))))))))
