(ns forestrysupport.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [forestrysupport.store :as store]))

;; ──────────────────────── Service-Order Retrieval ──────────────────────

(deftest service-order-test
  (testing "retrieve an existing service order"
    (let [order-data {:service-type :fire-support/patrol-and-fuelbreak-maintenance
                       :client-forest-operator-id "forest-42"}
          st {:service-orders {"order-001" order-data}}
          result (store/service-order st "order-001")]
      (is (= result order-data))))

  (testing "nonexistent service order returns nil"
    (let [st {:service-orders {}}
          result (store/service-order st "nonexistent")]
      (is (nil? result)))))

(deftest service-order-registered-test
  (testing "registered service order returns true"
    (let [st {:service-orders {"order-001" {:client-forest-operator-id "forest-42"}}}
          result (store/service-order-registered? st "order-001")]
      (is (true? result))))

  (testing "unregistered service order returns false"
    (let [st {:service-orders {}}
          result (store/service-order-registered? st "order-999")]
      (is (false? result)))))

;; ──────────────────────── Service-Order Status Checks ──────────────────────

(deftest service-order-already-logged-test
  (testing "logged service order is detected"
    (let [st {:service-orders {"order-001" {:logged? true}}}
          result (store/service-order-already-logged? st "order-001")]
      (is (true? result))))

  (testing "unlogged service order returns false"
    (let [st {:service-orders {"order-001" {:logged? false}}}
          result (store/service-order-already-logged? st "order-001")]
      (is (false? result))))

  (testing "nonexistent service order returns false"
    (let [st {:service-orders {}}
          result (store/service-order-already-logged? st "order-001")]
      (is (false? result)))))

;; ──────────────────────── Service-Order Logging ──────────────────────

(deftest log-service-record-test
  (testing "logging a service order marks it as logged"
    (let [st {:service-orders {}}
          order-data {:service-type :fire-support/patrol-and-fuelbreak-maintenance}
          result (store/log-service-record st "order-001" order-data)]
      (is (true? (get-in result [:service-orders "order-001" :logged?])))))

  (testing "logging preserves service-order data"
    (let [st {:service-orders {}}
          order-data {:service-type :fire-support/patrol-and-fuelbreak-maintenance :client-forest-operator-id "forest-42"}
          result (store/log-service-record st "order-001" order-data)]
      (is (= (:service-type (get-in result [:service-orders "order-001"])) :fire-support/patrol-and-fuelbreak-maintenance))
      (is (= (:client-forest-operator-id (get-in result [:service-orders "order-001"])) "forest-42")))))

;; ──────────────────────── Service-Order Scheduling ──────────────────────

(deftest mark-scheduled-test
  (testing "marking a service order marks it as scheduled"
    (let [st {:service-orders {"order-001" {:client-forest-operator-id "forest-42"}}}
          result (store/mark-scheduled st "order-001")]
      (is (true? (get-in result [:service-orders "order-001" :scheduled?]))))))

;; ──────────────────────── Audit Trail ──────────────────────

(deftest audit-trail-test
  (testing "audit trail is initially empty"
    (let [st {:facts []}
          result (store/audit-trail st)]
      (is (empty? result))))

  (testing "appended facts appear in audit trail"
    (let [st {:facts []}
          fact1 {:t :test-fact :detail "test 1"}
          fact2 {:t :test-fact :detail "test 2"}
          st' (store/append-fact st fact1)
          st'' (store/append-fact st' fact2)
          result (store/audit-trail st'')]
      (is (= (count result) 2))
      (is (= (first result) fact1))
      (is (= (second result) fact2)))))

(deftest append-fact-test
  (testing "appending a fact increases ledger length"
    (let [st {:facts []}
          fact {:t :governor-hold :op :log-service-record}
          result (store/append-fact st fact)]
      (is (= (count (:facts result)) 1))
      (is (= (first (:facts result)) fact)))))

;; ──────────────────────── MemStore (stateful actor-facing wrapper) ──────────────────────
;;
;; `MemStore` composes the pure functions above around a single mutable
;; atom -- it is what `forestrysupport.operation/build` closes over so a
;; real actor run can genuinely mutate the SSoT and append to the ledger.
;; Before this fix nothing in `src/` used the pure functions above at
;; all; these tests pin down the wrapper's own mutation semantics
;; directly (operation-test.cljc exercises it end-to-end through the
;; compiled graph).

(deftest mem-store-starts-empty-by-default
  (testing "an empty mem-store has no registered service orders and an empty ledger"
    (let [s (store/mem-store)]
      (is (= {:service-orders {} :facts []} (store/current s)))
      (is (empty? (store/ledger s))))))

(deftest mem-store-seeds-service-orders
  (testing "mem-store seeded with service orders makes them immediately readable via `current`"
    (let [order {:service-type :survey/timber-cruising :client-forest-operator-id "forest-1"}
          s (store/mem-store {"order-001" order})]
      (is (= order (store/service-order (store/current s) "order-001")))
      (is (true? (store/service-order-registered? (store/current s) "order-001")))
      (is (false? (store/service-order-registered? (store/current s) "order-999"))))))

(deftest mem-store-commit-log-service-record-mutates-in-place
  (testing "commit-log-service-record! is a genuine, visible-to-later-reads mutation"
    (let [s (store/mem-store {"order-001" {:service-type :survey/timber-cruising}})]
      (is (nil? (:logged? (store/service-order (store/current s) "order-001"))))
      (store/commit-log-service-record! s "order-001" {:service-type :survey/timber-cruising :field-notes "complete"})
      (let [order (store/service-order (store/current s) "order-001")]
        (is (true? (:logged? order)))
        (is (= "complete" (:field-notes order)))))))

(deftest mem-store-commit-schedule-mutates-in-place
  (testing "commit-schedule! is a genuine, visible-to-later-reads mutation"
    (let [s (store/mem-store {"order-001" {:service-type :survey/timber-cruising}})]
      (is (nil? (:scheduled? (store/service-order (store/current s) "order-001"))))
      (store/commit-schedule! s "order-001")
      (is (true? (:scheduled? (store/service-order (store/current s) "order-001")))))))

(deftest mem-store-append-ledger-accumulates
  (testing "append-ledger! accumulates facts in append order, visible via `ledger`"
    (let [s (store/mem-store)
          fact1 {:t :committed :op :schedule-field-operation}
          fact2 {:t :governor-hold :op :log-service-record}]
      (is (empty? (store/ledger s)))
      (store/append-ledger! s fact1)
      (is (= [fact1] (store/ledger s)))
      (store/append-ledger! s fact2)
      (is (= [fact1 fact2] (store/ledger s))
          "append-only -- earlier facts are never overwritten or reordered"))))
