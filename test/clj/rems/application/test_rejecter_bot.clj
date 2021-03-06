(ns ^:integration rems.application.test-rejecter-bot
  (:require [clojure.test :refer :all]
            [rems.api.services.blacklist :as blacklist]
            [rems.api.services.command :as command]
            [rems.application.rejecter-bot :as rejecter-bot]
            [rems.db.applications :as applications]
            [rems.db.test-data :as test-data]
            [rems.db.testing :refer [test-db-fixture rollback-db-fixture]]))

(use-fixtures :once test-db-fixture)
(use-fixtures :each rollback-db-fixture)

;; These tests are integration tests via rems.api.service.command
;; since we'd need to mock get-application to unit-test
;; run-rejecter-bot.

(deftest test-run-rejecter-bot
  (binding [command/*fail-on-process-manager-errors* true]
    (test-data/create-user! {:eppn rejecter-bot/bot-userid})
    (test-data/create-user! {:eppn "handler"})
    (test-data/create-user! {:eppn "user1"})
    (test-data/create-user! {:eppn "user2"})
    (test-data/create-user! {:eppn "baddie"})
    (test-data/create-user! {:eppn "accomplice"})
    (test-data/create-user! {:eppn "innocent"})
    (let [res1 (test-data/create-resource! {:resource-ext-id "res1"})
          res2 (test-data/create-resource! {:resource-ext-id "res2"})
          wf (test-data/create-workflow! {:type :workflow/default
                                          :handlers [rejecter-bot/bot-userid
                                                     "handler"]})
          cat1 (test-data/create-catalogue-item! {:title {:en "cat1"}
                                                  :workflow-id wf
                                                  :resource-id res1})
          cat2 (test-data/create-catalogue-item! {:title {:fi "cat2"}
                                                  :workflow-id wf
                                                  :resource-id res2})]
      (testing "rejecting submitted applications:"
        (blacklist/add-user-to-blacklist! "handler"
                                          {:blacklist/user {:userid "user1"}
                                           :blacklist/resource {:resource/ext-id "res1"}})
        (testing "blacklisted user"
          (let [app-id (test-data/create-application! {:actor "user1"
                                                       :catalogue-item-ids [cat1]})]
            (test-data/command! {:type :application.command/submit
                                 :application-id app-id
                                 :actor "user1"})
            (is (= :application.state/rejected (:application/state (applications/get-application app-id))))))
        (testing "blacklisted user, different resource"
          (let [app-id (test-data/create-application! {:actor "user1"
                                                       :catalogue-item-ids [cat2]})]
            (test-data/command! {:type :application.command/submit
                                 :application-id app-id
                                 :actor "user1"})
            (is (= :application.state/submitted (:application/state (applications/get-application app-id))))))
        (testing "unblacklisted user"
          (let [app-id (test-data/create-application! {:actor "user2"
                                                       :catalogue-item-ids [cat1]})]
            (test-data/command! {:type :application.command/submit
                                 :application-id app-id
                                 :actor "user2"})
            (is (= :application.state/submitted (:application/state (applications/get-application app-id)))))))
      (testing "rejecting on revoke:"
        (let [app-1 (test-data/create-application! {:actor "baddie"
                                                    :catalogue-item-ids [cat1]})
              app-2 (test-data/create-application! {:actor "baddie"
                                                    :catalogue-item-ids [cat2]})
              app-12 (test-data/create-application! {:actor "baddie"
                                                     :catalogue-item-ids [cat1 cat2]})
              app-1-innocent (test-data/create-application! {:actor "innocent"
                                                             :catalogue-item-ids [cat1]})
              app-1-member (test-data/create-application! {:actor "innocent"
                                                           :catalogue-item-ids [cat1]})
              accomplice-app-1 (test-data/create-application! {:actor "accomplice"
                                                               :catalogue-item-ids [cat1]})]
          (testing "set up applications"
            (test-data/command! {:type :application.command/submit
                                 :application-id app-1
                                 :actor "baddie"})
            (test-data/command! {:type :application.command/add-member
                                 :application-id app-1
                                 :member {:userid "accomplice"}
                                 :actor "handler"})
            (test-data/command! {:type :application.command/submit
                                 :application-id app-2
                                 :actor "baddie"})
            (test-data/command! {:type :application.command/submit
                                 :application-id app-12
                                 :actor "baddie"})
            (test-data/command! {:type :application.command/add-member
                                 :application-id app-12
                                 :member {:userid "accomplice"}
                                 :actor "handler"})
            (test-data/command! {:type :application.command/submit
                                 :application-id app-1-innocent
                                 :actor "innocent"})
            (test-data/command! {:type :application.command/submit
                                 :application-id app-1-member
                                 :actor "innocent"})
            (test-data/command! {:type :application.command/add-member
                                 :application-id app-1-member
                                 :member {:userid "baddie"}
                                 :actor "handler"})
            (test-data/command! {:type :application.command/submit
                                 :application-id accomplice-app-1
                                 :actor "accomplice"})
            (test-data/command! {:type :application.command/approve
                                 :application-id app-12
                                 :actor "handler"})
            (is (= :application.state/submitted (:application/state (applications/get-application app-1))))
            (is (= :application.state/submitted (:application/state (applications/get-application app-2))))
            (is (= :application.state/approved (:application/state (applications/get-application app-12))))
            (is (= :application.state/submitted (:application/state (applications/get-application app-1-innocent))))
            (is (= :application.state/submitted (:application/state (applications/get-application app-1-member))))
            (is (= :application.state/submitted (:application/state (applications/get-application accomplice-app-1)))))
          (testing "revoke"
            (test-data/command! {:type :application.command/revoke
                                 :application-id app-12
                                 :actor "handler"})
            (is (= :application.state/revoked (:application/state (applications/get-application app-12)))))
          (testing "related applications are rejected"
            (is (= :application.state/rejected (:application/state (applications/get-application app-1))))
            (is (= :application.state/rejected (:application/state (applications/get-application app-2))))
            (is (= :application.state/rejected (:application/state (applications/get-application app-1-member))))
            (is (= :application.state/rejected (:application/state (applications/get-application accomplice-app-1)))))
          (testing "unrelated applications are not rejected"
            (is (= :application.state/submitted (:application/state (applications/get-application app-1-innocent))))))))))
