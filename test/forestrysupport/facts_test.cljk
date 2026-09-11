(ns forestrysupport.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [forestrysupport.facts :as facts]))

;; ──────────────────────── Service-Type Lookups ──────────────────────

(deftest service-type-by-id-test
  (testing "fire-protection-support service type exists"
    (let [s (facts/service-type-by-id :fire-support/patrol-and-fuelbreak-maintenance)]
      (is (some? s))
      (is (= (:id s) :fire-support/patrol-and-fuelbreak-maintenance))
      (is (true? (:field-equipment-operation? s)))
      (is (= (:equipment-inspection-interval-days s) 30))))

  (testing "in-forest log-haul service type exists"
    (let [s (facts/service-type-by-id :transport/in-forest-log-haul)]
      (is (some? s))
      (is (true? (:field-equipment-operation? s)))
      (is (= (:max-load-kg s) 20000.0))
      (is (nil? (:max-wind-speed-kmh s)))))

  (testing "timber-cruising survey service type exists and has no equipment spec"
    (let [s (facts/service-type-by-id :survey/timber-cruising)]
      (is (some? s))
      (is (false? (:field-equipment-operation? s)))
      (is (nil? (:equipment-inspection-interval-days s)))
      (is (nil? (:max-wind-speed-kmh s)))
      (is (nil? (:min-riparian-buffer-m s)))
      (is (nil? (:max-load-kg s)))))

  (testing "seed-collection survey service type exists and has no equipment spec"
    (let [s (facts/service-type-by-id :survey/seed-collection)]
      (is (some? s))
      (is (false? (:field-equipment-operation? s)))
      (is (nil? (:equipment-inspection-interval-days s)))))

  (testing "nonexistent service type returns nil"
    (is (nil? (facts/service-type-by-id :nonexistent/service)))))

;; ──────────────────────── Jurisdiction Lookups ──────────────────────

(deftest jurisdiction-by-id-test
  (testing "JP MAFF jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :jp/maff)]
      (is (some? j))
      (is (contains? (set (:required-evidence j)) :operator-certification))))

  (testing "US state forest practices act jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :us/state-forest-practices-act)]
      (is (some? j))
      (is (contains? (set (:required-evidence j)) :riparian-buffer-assessment))))

  (testing "Canada BC FRPA jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :ca/bc-frpa)]
      (is (some? j))
      (is (contains? (set (:required-evidence j)) :equipment-inspection-record))))

  (testing "nonexistent jurisdiction returns nil"
    (is (nil? (facts/jurisdiction-by-id :xx/unknown)))))

;; ──────────────────────── Forestry-Support-Service Safety Predicates ────

(deftest operator-certification-current-test
  (let [fire-support (facts/service-type-by-id :fire-support/patrol-and-fuelbreak-maintenance)
        cruising (facts/service-type-by-id :survey/timber-cruising)]
    (testing "certification expiring in the future is current"
      (is (true? (facts/operator-certification-current? 2000 1000 fire-support))))

    (testing "certification expiring in the past is not current"
      (is (false? (facts/operator-certification-current? 500 1000 fire-support))))

    (testing "survey service type never needs a certification"
      (is (false? (facts/operator-certification-current? 2000 1000 cruising))))))

(deftest equipment-inspection-current-test
  (let [fire-support (facts/service-type-by-id :fire-support/patrol-and-fuelbreak-maintenance)
        cruising (facts/service-type-by-id :survey/timber-cruising)
        now 1000000000
        ten-days-ago (- now (* 10 24 60 60 1000))
        hundred-days-ago (- now (* 100 24 60 60 1000))]
    (testing "recent inspection is current"
      (is (true? (facts/equipment-inspection-current? ten-days-ago now fire-support))))

    (testing "overdue inspection is not current"
      (is (false? (facts/equipment-inspection-current? hundred-days-ago now fire-support))))

    (testing "survey service type never needs inspection"
      (is (false? (facts/equipment-inspection-current? ten-days-ago now cruising))))))

(deftest wind-speed-in-range-test
  (let [fire-support (facts/service-type-by-id :fire-support/patrol-and-fuelbreak-maintenance)
        log-haul (facts/service-type-by-id :transport/in-forest-log-haul)]
    (testing "wind speed at or below ceiling passes"
      (is (true? (facts/wind-speed-in-range? 40.0 fire-support)))
      (is (true? (facts/wind-speed-in-range? 5.0 fire-support))))

    (testing "wind speed above ceiling fails"
      (is (false? (facts/wind-speed-in-range? 45.0 fire-support))))

    (testing "log-haul service type has no wind-speed ceiling"
      (is (false? (facts/wind-speed-in-range? 5.0 log-haul))))))

(deftest riparian-buffer-in-range-test
  (let [fire-support (facts/service-type-by-id :fire-support/patrol-and-fuelbreak-maintenance)
        cruising (facts/service-type-by-id :survey/timber-cruising)]
    (testing "buffer distance at or above minimum passes"
      (is (true? (facts/riparian-buffer-in-range? 10.0 fire-support)))
      (is (true? (facts/riparian-buffer-in-range? 20.0 fire-support))))

    (testing "buffer distance below minimum fails"
      (is (false? (facts/riparian-buffer-in-range? 5.0 fire-support))))

    (testing "survey service type has no riparian-buffer minimum"
      (is (false? (facts/riparian-buffer-in-range? 50.0 cruising))))))

(deftest load-in-range-test
  (let [log-haul (facts/service-type-by-id :transport/in-forest-log-haul)
        fire-support (facts/service-type-by-id :fire-support/patrol-and-fuelbreak-maintenance)]
    (testing "load at or below maximum passes"
      (is (true? (facts/load-in-range? 20000.0 log-haul)))
      (is (true? (facts/load-in-range? 5000.0 log-haul))))

    (testing "load above maximum fails"
      (is (false? (facts/load-in-range? 25000.0 log-haul))))

    (testing "fire-support service type has no load limit"
      (is (false? (facts/load-in-range? 1000.0 fire-support))))))

;; ──────────────────────── Evidence Completeness ──────────────────────

(deftest required-evidence-satisfied-test
  (testing "complete evidence checklist passes"
    (let [j (facts/jurisdiction-by-id :jp/maff)
          evidence [:service-order-record :forest-boundary-map :field-log
                    :operator-certification :equipment-inspection-record :riparian-buffer-assessment]]
      (is (true? (facts/required-evidence-satisfied? j evidence)))))

  (testing "incomplete evidence fails"
    (let [j (facts/jurisdiction-by-id :jp/maff)
          evidence [:service-order-record :forest-boundary-map]]
      (is (false? (facts/required-evidence-satisfied? j evidence)))))

  (testing "raw jurisdiction id call convention also works"
    (let [evidence [:service-order-record :forest-boundary-map :field-log
                    :operator-certification :equipment-inspection-record :riparian-buffer-assessment]]
      (is (true? (facts/required-evidence-satisfied? :us/state-forest-practices-act evidence)))))

  (testing "unknown jurisdiction never satisfies"
    (is (false? (facts/required-evidence-satisfied? :xx/unknown [])))))
