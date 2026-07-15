(ns forestrysupport.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [forestrysupport.registry :as registry]))

;; ──────────────────────── Operator Certification ──────────────────────

(deftest operator-certification-expired-test
  (testing "expiry in the future returns false (no violation)"
    (is (false? (registry/operator-certification-expired? 2000 1000))))

  (testing "expiry exactly now returns false"
    (is (false? (registry/operator-certification-expired? 1000 1000))))

  (testing "expiry in the past returns true (violation)"
    (is (true? (registry/operator-certification-expired? 500 1000)))))

;; ──────────────────────── Equipment Inspection ──────────────────────

(deftest equipment-inspection-overdue-test
  (testing "recent inspection returns false (no violation)"
    (let [now 1000000000
          ten-days-ago (- now (* 10 24 60 60 1000))]
      (is (false? (registry/equipment-inspection-overdue? ten-days-ago now 30)))))

  (testing "overdue inspection returns true (violation)"
    (let [now 1000000000
          hundred-days-ago (- now (* 100 24 60 60 1000))]
      (is (true? (registry/equipment-inspection-overdue? hundred-days-ago now 30)))))

  (testing "interval-days varies per service type -- 60-day interval accepts a 40-day-old inspection"
    (let [now 1000000000
          forty-days-ago (- now (* 40 24 60 60 1000))]
      (is (false? (registry/equipment-inspection-overdue? forty-days-ago now 60)))
      (is (true? (registry/equipment-inspection-overdue? forty-days-ago now 30))))))

;; ──────────────────────── Wind Speed ──────────────────────

(deftest wind-speed-exceeded-test
  (testing "wind at or below ceiling returns false (no violation)"
    (is (false? (registry/wind-speed-exceeded? 40.0 40.0)))
    (is (false? (registry/wind-speed-exceeded? 10.0 40.0))))

  (testing "wind above ceiling returns true (violation)"
    (is (true? (registry/wind-speed-exceeded? 45.0 40.0)))))

;; ──────────────────────── Riparian Buffer ──────────────────────

(deftest riparian-buffer-violated-test
  (testing "buffer at or above minimum returns false (no violation)"
    (is (false? (registry/riparian-buffer-violated? 10.0 10.0)))
    (is (false? (registry/riparian-buffer-violated? 20.0 10.0))))

  (testing "buffer below minimum returns true (violation)"
    (is (true? (registry/riparian-buffer-violated? 5.0 10.0)))))

;; ──────────────────────── Load Limit ──────────────────────

(deftest load-limit-exceeded-test
  (testing "load at or below maximum returns false (no violation)"
    (is (false? (registry/load-limit-exceeded? 20000.0 20000.0)))
    (is (false? (registry/load-limit-exceeded? 5000.0 20000.0))))

  (testing "load above maximum returns true (violation)"
    (is (true? (registry/load-limit-exceeded? 25000.0 20000.0)))))
