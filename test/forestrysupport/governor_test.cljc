(ns forestrysupport.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [forestrysupport.governor :as governor]))

(def ^:private now-ms #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))
(def ^:private ten-days-ago (- now-ms (* 10 24 60 60 1000)))
(def ^:private hundred-days-ago (- now-ms (* 100 24 60 60 1000)))
(def ^:private ten-days-from-now (+ now-ms (* 10 24 60 60 1000)))

(def ^:private evidence-checklist
  [:service-order-record :forest-boundary-map :field-log
   :operator-certification :equipment-inspection-record :riparian-buffer-assessment])

(def ^:private clean-fire-support-order
  "Baseline clean service order for a field-equipment-operation service
  type (fire-protection support) -- has certification/inspection/wind/
  buffer specs, but no load spec (fire-protection support hauls no
  logs)."
  {:service-type :fire-support/patrol-and-fuelbreak-maintenance
   :jurisdiction :jp/maff
   :client-forest-operator-id "forest-42"
   :operator-certification-expiry-date ten-days-from-now
   :equipment-last-inspection-date ten-days-ago
   :wind-speed-kmh 10.0
   :riparian-buffer-actual-m 20.0
   :evidence-checklist evidence-checklist})

(def ^:private clean-log-haul-order
  "Baseline clean service order for a field-equipment-operation service
  type (in-forest log transport) -- has certification/inspection/buffer/
  load specs, but no wind-speed spec (log-haul travel is not
  wind-sensitive the way canopy-adjacent chainsaw work is)."
  {:service-type :transport/in-forest-log-haul
   :jurisdiction :jp/maff
   :client-forest-operator-id "forest-88"
   :operator-certification-expiry-date ten-days-from-now
   :equipment-last-inspection-date ten-days-ago
   :riparian-buffer-actual-m 20.0
   :load-actual-kg 15000.0
   :evidence-checklist evidence-checklist})

(def ^:private clean-cruising-order
  "Baseline clean service order for a survey/collection service type
  (timber cruising) -- has NO field-equipment-operation safety-window
  fields at all."
  {:service-type :survey/timber-cruising
   :jurisdiction :jp/maff
   :client-forest-operator-id "forest-77"
   :evidence-checklist evidence-checklist})

;; ──────────────────────── Registration Invariant ──────────────────────

(deftest service-order-not-registered-violation-test
  (testing "log-service-record against a never-registered service order is a hard block"
    (let [store {:service-orders {}}
          req {:op :log-service-record :subject "order-999"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :service-order-not-registered) (:violations result)))))

  (testing "schedule-field-operation against a never-registered service order is a hard block"
    (let [store {:service-orders {}}
          req {:op :schedule-field-operation :subject "order-999"}
          prop {:cites [] :value {} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :service-order-not-registered) (:violations result)))))

  (testing "flag-forest-health-concern against a never-registered service order is a hard block"
    (let [store {:service-orders {}}
          req {:op :flag-forest-health-concern :subject "order-999"}
          prop {:cites [{:spec "Field-Report"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :service-order-not-registered) (:violations result)))))

  (testing "order-supplies against a never-registered service order is a hard block"
    (let [store {:service-orders {}}
          req {:op :order-supplies :subject "order-999"}
          prop {:cites [{:spec "Supplier-Catalog"}] :value {:jurisdiction :jp/maff :cost-usd 100} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :service-order-not-registered) (:violations result))))))

;; ──────────────────────── Spec Basis ──────────────────────

(deftest spec-basis-violation-test
  (testing "proposal with no jurisdiction citation is a hard violation"
    (let [store {:service-orders {"order-001" clean-fire-support-order}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [] :value {:jurisdiction nil}}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :no-spec-basis) (:violations result)))))

  (testing "proposal with proper citation passes spec basis check"
    (let [store {:service-orders {"order-001" clean-fire-support-order}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result))))))

;; ──────────────────────── Operator Certification Violations ──────────────────────

(deftest operator-certification-expired-violation-test
  (testing "expired operator certification triggers hard violation"
    (let [store {:service-orders {"order-001" (assoc clean-fire-support-order
                                                       :operator-certification-expiry-date hundred-days-ago)}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :operator-certification-expired) (:violations result)))))

  (testing "current operator certification passes"
    (let [store {:service-orders {"order-001" clean-fire-support-order}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result)))))

  (testing "survey service type never triggers this rule"
    (let [store {:service-orders {"order-002" clean-cruising-order}}
          req {:op :log-service-record :subject "order-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :operator-certification-expired) (:violations result)))))))

;; ──────────────────────── Equipment Inspection Violations ──────────────────────

(deftest equipment-inspection-overdue-violation-test
  (testing "overdue equipment inspection triggers hard violation"
    (let [store {:service-orders {"order-001" (assoc clean-fire-support-order
                                                       :equipment-last-inspection-date hundred-days-ago)}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :equipment-inspection-overdue) (:violations result)))))

  (testing "in-forest log-haul has a 60-day interval, wider than fire-support's 30-day"
    (let [forty-days-ago (- now-ms (* 40 24 60 60 1000))
          store {:service-orders {"order-002" (assoc clean-log-haul-order
                                                       :equipment-last-inspection-date forty-days-ago)}}
          req {:op :log-service-record :subject "order-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result)))))

  (testing "survey service type never triggers this rule"
    (let [store {:service-orders {"order-003" clean-cruising-order}}
          req {:op :log-service-record :subject "order-003"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :equipment-inspection-overdue) (:violations result)))))))

;; ──────────────────────── Wind Speed Violations ──────────────────────

(deftest wind-speed-exceeded-violation-test
  (testing "wind speed above the service type's ceiling triggers hard violation"
    (let [store {:service-orders {"order-001" (assoc clean-fire-support-order :wind-speed-kmh 50.0)}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :wind-speed-exceeded) (:violations result)))))

  (testing "in-forest log-haul service type never triggers this rule (no wind-speed ceiling)"
    (let [store {:service-orders {"order-002" clean-log-haul-order}}
          req {:op :log-service-record :subject "order-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :wind-speed-exceeded) (:violations result))))))

  (testing "survey service type never triggers this rule"
    (let [store {:service-orders {"order-003" clean-cruising-order}}
          req {:op :log-service-record :subject "order-003"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :wind-speed-exceeded) (:violations result)))))))

;; ──────────────────────── Riparian Buffer Violations ──────────────────────

(deftest riparian-buffer-violated-violation-test
  (testing "riparian buffer narrower than minimum triggers hard violation"
    (let [store {:service-orders {"order-001" (assoc clean-fire-support-order :riparian-buffer-actual-m 2.0)}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :riparian-buffer-violated) (:violations result)))))

  (testing "log-haul's minimum riparian buffer (15m) is stricter than fire-support's (10m)"
    (let [store {:service-orders {"order-002" (assoc clean-log-haul-order :riparian-buffer-actual-m 12.0)}}
          req {:op :log-service-record :subject "order-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :riparian-buffer-violated) (:violations result)))))

  (testing "buffer zone at or above minimum passes"
    (let [store {:service-orders {"order-003" clean-fire-support-order}}
          req {:op :log-service-record :subject "order-003"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result)))))

  (testing "survey service type never triggers this rule"
    (let [store {:service-orders {"order-004" clean-cruising-order}}
          req {:op :log-service-record :subject "order-004"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :riparian-buffer-violated) (:violations result)))))))

;; ──────────────────────── Load Limit Violations ──────────────────────

(deftest load-limit-exceeded-violation-test
  (testing "load above maximum triggers hard violation"
    (let [store {:service-orders {"order-001" (assoc clean-log-haul-order :load-actual-kg 25000.0)}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :load-limit-exceeded) (:violations result)))))

  (testing "load at or below maximum passes"
    (let [store {:service-orders {"order-002" clean-log-haul-order}}
          req {:op :log-service-record :subject "order-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result)))))

  (testing "fire-support service type never triggers this rule (no load hauled)"
    (let [store {:service-orders {"order-003" clean-fire-support-order}}
          req {:op :log-service-record :subject "order-003"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :load-limit-exceeded) (:violations result))))))

  (testing "survey service type never triggers this rule"
    (let [store {:service-orders {"order-004" clean-cruising-order}}
          req {:op :log-service-record :subject "order-004"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :load-limit-exceeded) (:violations result)))))))

;; ──────────────────────── Evidence Completeness ──────────────────────

(deftest evidence-incomplete-violation-test
  (testing "incomplete evidence checklist triggers hard violation"
    (let [store {:service-orders {"order-001" (assoc clean-fire-support-order
                                                       :evidence-checklist [:service-order-record])}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :evidence-incomplete) (:violations result))))))

;; ──────────────────────── Forestry-Equipment / Fire-Tactic-Decision Block ──────

(deftest forestry-equipment-or-fire-tactic-decision-blocked-violation-test
  (testing "a proposal covertly requesting direct forestry-equipment control is a hard, permanent block"
    (let [store {:service-orders {"order-001" clean-fire-support-order}}
          req {:op :schedule-field-operation :subject "order-001"}
          prop {:cites [] :value {:operate-forestry-equipment? true} :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :forestry-equipment-or-fire-tactic-decision-blocked) (:violations result)))))

  (testing "a proposal covertly requesting a final fire-suppression-tactic decision is a hard, permanent block"
    (let [store {:service-orders {"order-001" clean-fire-support-order}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}]
                :value {:jurisdiction :jp/maff :finalize-fire-suppression-tactic-decision? true}
                :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :forestry-equipment-or-fire-tactic-decision-blocked) (:violations result))))))

;; ──────────────────────── Forest-Health Flag Violations ──────────────────────

(deftest forest-health-flag-unresolved-violation-test
  (testing "an unresolved forest-health flag triggers hard violation"
    (let [store {:service-orders {"order-001" (assoc clean-fire-support-order
                                                       :forest-health-concern-raised? true
                                                       :forest-health-concern-resolved? false)}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :forest-health-flag-unresolved) (:violations result)))))

  (testing "a resolved forest-health flag does not trigger this rule"
    (let [store {:service-orders {"order-002" (assoc clean-fire-support-order
                                                       :forest-health-concern-raised? true
                                                       :forest-health-concern-resolved? true)}}
          req {:op :log-service-record :subject "order-002"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :forest-health-flag-unresolved) (:violations result)))))))

;; ──────────────────────── Escalation (Low Confidence) ──────────────────────

(deftest low-confidence-escalation-test
  (testing "low confidence proposal escalates even when hard checks pass"
    (let [store {:service-orders {"order-001" clean-fire-support-order}}
          req {:op :schedule-field-operation :subject "order-001"}
          prop {:cites [] :value {} :confidence 0.5}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── High Stakes Escalation ──────────────────────

(deftest high-stakes-escalation-test
  (testing "log-service-record escalates even when all checks pass"
    (let [store {:service-orders {"order-001" clean-fire-support-order}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.95}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── Forest-Health Concern Always Escalates ──────────────────────

(deftest forest-health-concern-always-escalates-test
  (testing "a clean flag-forest-health-concern proposal is never auto-ok"
    (let [store {:service-orders {"order-001" clean-fire-support-order}}
          req {:op :flag-forest-health-concern :subject "order-001"}
          prop {:cites [{:spec "Field-Report"}] :value {:jurisdiction :jp/maff} :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── High-Cost Supply Order Escalation ──────────────────────

(deftest high-cost-supply-order-escalation-test
  (testing "a supply order above the cost threshold escalates even when clean"
    (let [store {:service-orders {"order-001" clean-fire-support-order}}
          req {:op :order-supplies :subject "order-001"}
          prop {:cites [{:spec "Supplier-Catalog"}]
                :value {:jurisdiction :jp/maff :cost-usd 10000}
                :confidence 0.9}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result)))))

  (testing "a supply order at or below the cost threshold does not force escalation"
    (let [store {:service-orders {"order-002" clean-fire-support-order}}
          req {:op :order-supplies :subject "order-002"}
          prop {:cites [{:spec "Supplier-Catalog"}]
                :value {:jurisdiction :jp/maff :cost-usd 1000}
                :confidence 0.9}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:ok? result))))))

;; ──────────────────────── Already Logged Violation ──────────────────────

(deftest already-logged-violation-test
  (testing "service order already logged triggers hard violation"
    (let [store {:service-orders {"order-001"
                                   {:service-type :fire-support/patrol-and-fuelbreak-maintenance
                                    :logged? true}}}
          req {:op :log-service-record :subject "order-001"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :jp/maff} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :already-logged) (:violations result))))))

;; ──────────────────────── Op-Not-Allowed Violation ──────────────────────

(deftest op-not-allowed-violation-test
  (testing "an out-of-allowlist op (e.g. direct forestry-equipment operation) is a hard, permanent block"
    (let [store {:service-orders {"order-001" clean-fire-support-order}}
          req {:op :operate-chainsaw :subject "order-001"}
          prop {:cites [{:spec "Chainsaw-Manual"}] :value {:jurisdiction :jp/maff} :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :op-not-allowed) (:violations result))))))

;; ──────────────────────── Effect-Not-Propose Violation ──────────────────────

(deftest effect-not-propose-violation-test
  (testing "a proposal asserting a non-:propose effect is a hard, permanent block"
    (let [store {:service-orders {"order-001" clean-fire-support-order}}
          req {:op :schedule-field-operation :subject "order-001"}
          prop {:effect :commit :cites [] :value {} :confidence 0.9}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :effect-not-propose) (:violations result))))))
