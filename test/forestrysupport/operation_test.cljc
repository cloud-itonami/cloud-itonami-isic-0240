(ns forestrysupport.operation-test
  "Integration tests for `forestrysupport.operation/build` -- builds the
  REAL compiled `langgraph.graph` StateGraph and runs it end-to-end via
  `langgraph.graph/run*` through commit / hard-hold / escalate-approve /
  escalate-reject routes. This namespace replaces the previous
  `operation-test`, which called `operation/run-operation` directly with
  a hand-constructed `proposal` map -- it never built a graph, never went
  through the Advisor, and never touched the ledger at all (there was no
  ledger call in `run-operation`'s own body).

  These tests prove: (1) the compiled graph is real and reachable
  end-to-end, (2) the audit ledger (`forestrysupport.store/append-ledger!`)
  is genuinely wired into the `:commit`/`:hold` nodes and stays EMPTY
  until a real commit/hold actually executes -- not merely reachable in
  principle, (3) the two SSoT mutations (`commit-log-service-record!`/
  `commit-schedule!`) only fire on the real `:commit` node, never on
  `:hold`, and (4) the Advisor's own proposal (confidence, cited spec,
  declared cost) -- not a hardcoded value -- genuinely drives the
  Governor's routing and what lands in the ledger, proven by swapping in
  custom `Advisor` implementations and observing the outcome change."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [forestrysupport.advisor :as advisor]
            [forestrysupport.operation :as operation]
            [forestrysupport.store :as store]))

(def ^:private now-ms #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))
(def ^:private ten-days-ago (- now-ms (* 10 24 60 60 1000)))
(def ^:private ten-days-from-now (+ now-ms (* 10 24 60 60 1000)))

(def ^:private clean-fire-support-order
  "A registered, fully-compliant field-equipment-operation service order
  (fire-protection-support) -- current certification/inspection, wind and
  riparian buffer within range, evidence checklist complete. Only
  :log-service-record exercises all of these checks; the other three ops
  need only registration (and, for :order-supplies/:flag-forest-health-
  concern, a jurisdiction to cite)."
  {:service-type :fire-support/patrol-and-fuelbreak-maintenance
   :jurisdiction :jp/maff
   :client-forest-operator-id "forest-42"
   :operator-certification-expiry-date ten-days-from-now
   :equipment-last-inspection-date ten-days-ago
   :wind-speed-kmh 10.0
   :riparian-buffer-actual-m 20.0
   :evidence-checklist [:service-order-record :forest-boundary-map :field-log
                        :operator-certification :equipment-inspection-record
                        :riparian-buffer-assessment]})

(defn- exec [actor tid request]
  (g/run* actor {:request request} {:thread-id tid}))

;; ──────────────────────── Commit Path (auto-commit) ──────────────────────

(deftest commit-path-clean-schedule-operation-mutates-ssot
  (testing "a clean :schedule-field-operation (not a high-stakes/always-
            escalate op) commits through the real compiled graph, appends
            to the audit ledger, AND marks the service order scheduled"
    (let [s (store/mem-store {"order-001" {:service-type :survey/timber-cruising}})
          actor (operation/build s)
          _ (is (empty? (store/ledger s)) "ledger is empty before the actor ever runs")
          result (exec actor "t-commit" {:op :schedule-field-operation :subject "order-001"})
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :commit (:decision state)))
      (let [ledg (store/ledger s)]
        (is (= 1 (count ledg)))
        (is (= :committed (:t (first ledg))))
        (is (= :schedule-field-operation (:op (first ledg))))
        (is (= "order-001" (:subject (first ledg)))))
      (is (true? (:scheduled? (store/service-order (store/current s) "order-001")))
          "store/commit-schedule! (SSoT mutation) is preserved on the graph's :commit node"))))

;; ──────────────────────── Hard-Hold Paths ──────────────────────

(deftest hard-hold-path-op-not-allowed
  (testing "an out-of-allowlist op (direct forestry-equipment operation) is
            a HARD, permanent block -- the real graph routes straight to
            :hold (no interrupt, no human-approval detour) and durably
            records the hold fact, WITHOUT ever touching the SSoT"
    (let [s (store/mem-store {"order-002" clean-fire-support-order})
          actor (operation/build s)
          result (exec actor "t-hold-op" {:op :operate-chainsaw :subject "order-002"})
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :hold (:decision state)))
      (let [ledg (store/ledger s)]
        (is (= 1 (count ledg)))
        (is (= :governor-hold (:t (first ledg))))
        (is (some #(= (:rule %) :op-not-allowed) (:violations (first ledg)))))
      (is (nil? (:scheduled? (store/service-order (store/current s) "order-002"))))
      (is (nil? (:logged? (store/service-order (store/current s) "order-002")))))))

(deftest hard-hold-path-unregistered-service-order
  (testing "any op against a never-registered service order is a HARD
            block -- the real graph routes to :hold"
    (let [s (store/mem-store {})
          actor (operation/build s)
          result (exec actor "t-hold-unreg" {:op :schedule-field-operation :subject "order-999"})]
      (is (= :hold (:decision (:state result))))
      (let [ledg (store/ledger s)]
        (is (= 1 (count ledg)))
        (is (some #(= (:rule %) :service-order-not-registered) (:violations (first ledg))))))))

(deftest hard-hold-path-effect-not-propose
  (testing "a proposal asserting a non-:propose effect is a HARD, permanent
            block -- proven with a swapped-in Advisor that genuinely
            asserts :effect :commit (never a hardcoded governor pass)"
    (let [s (store/mem-store {"order-010" clean-fire-support-order})
          direct-commit-advisor
          (reify advisor/Advisor
            (advise [_ request st]
              (let [order (store/service-order st (:subject request))]
                {:op (:op request) :subject (:subject request) :effect :commit
                 :cites [{:spec (:jurisdiction order)}]
                 :value {:jurisdiction (:jurisdiction order)}
                 :confidence 0.95
                 :summary "illegitimate direct-commit claim"})))
          actor (operation/build s {:advisor direct-commit-advisor})
          result (exec actor "t-effect" {:op :log-service-record :subject "order-010"})]
      (is (= :hold (:decision (:state result))))
      (is (some #(= (:rule %) :effect-not-propose) (:violations (first (store/ledger s))))))))

;; ──────────────────────── Escalate -> Approve / Reject ──────────────────────

(deftest escalate-then-approve-commits-and-mutates-ssot
  (testing ":log-service-record ALWAYS escalates -- the real graph
            GENUINELY interrupts (checkpointed) at :request-approval; a
            human dispatcher approve! resumes the SAME compiled graph and
            commits via the graph's own :request-approval -> :commit edge,
            durably appending to the ledger AND marking the order logged"
    (let [s (store/mem-store {"order-004" clean-fire-support-order})
          actor (operation/build s)
          held (exec actor "t-escalate" {:op :log-service-record :subject "order-004"})]
      (is (= :interrupted (:status held)))
      (is (= [:request-approval] (:frontier held)))
      (is (empty? (store/ledger s)) "not yet committed -- awaiting human sign-off")
      (is (nil? (:logged? (store/service-order (store/current s) "order-004"))))
      (let [approved (g/run* actor {:approval {:status :approved :by "dispatcher-01"}}
                             {:thread-id "t-escalate" :resume? true})
            approved-state (:state approved)]
        (is (= :done (:status approved)))
        (is (= :commit (:decision approved-state)))
        (let [ledg (store/ledger s)]
          (is (= 1 (count ledg)))
          (is (= :committed (:t (first ledg))))
          (is (= :log-service-record (:op (first ledg))))
          (is (= "dispatcher-01" (:approved-by (first ledg)))))
        (is (true? (:logged? (store/service-order (store/current s) "order-004")))
            "store/commit-log-service-record! (SSoT mutation) fires only on genuine approval")))))

(deftest escalate-then-reject-holds-ssot-unchanged
  (testing "a human dispatcher rejecting an escalated request routes to
            :hold via the :request-approval node's own decision, durably
            records the rejection, and NEVER mutates the SSoT"
    (let [s (store/mem-store {"order-005" clean-fire-support-order})
          actor (operation/build s)
          _held (exec actor "t-reject" {:op :log-service-record :subject "order-005"})
          rejected (g/run* actor {:approval {:status :rejected :by "dispatcher-01"}}
                           {:thread-id "t-reject" :resume? true})
          rejected-state (:state rejected)]
      (is (= :done (:status rejected)))
      (is (= :hold (:decision rejected-state)))
      (let [ledg (store/ledger s)]
        (is (= 1 (count ledg)))
        (is (= :approval-rejected (:t (first ledg)))))
      (is (nil? (:logged? (store/service-order (store/current s) "order-005")))
          "a rejected proposal never reaches store/commit-log-service-record!"))))

(deftest forest-health-concern-always-escalates
  (testing "a clean :flag-forest-health-concern proposal is never auto-ok,
            regardless of advisor confidence -- proven through the real
            compiled graph, not just the governor unit"
    (let [s (store/mem-store {"order-006" clean-fire-support-order})
          actor (operation/build s)
          held (exec actor "t-health" {:op :flag-forest-health-concern :subject "order-006"})]
      (is (= :interrupted (:status held)))
      (is (= [:request-approval] (:frontier held)))
      (is (empty? (store/ledger s))))))

;; ──────────────────────── Advisor Is Genuinely Consulted ──────────────────────

(deftest advisor-confidence-swap-changes-routing
  (testing "the SAME request that auto-commits under the default
            MockAdvisor (see commit-path-clean-schedule-operation-mutates-
            ssot, confidence 0.9) instead ESCALATES when a swapped-in
            Advisor genuinely returns low confidence -- proof the
            :advise node's real output, not a hardcoded governor pass,
            drives the :decide routing"
    (let [s (store/mem-store {"order-007" {:service-type :survey/timber-cruising}})
          low-confidence-advisor
          (reify advisor/Advisor
            (advise [_ request _st]
              {:op (:op request) :subject (:subject request) :effect :propose
               :cites [] :value {} :confidence 0.3
               :summary "low-confidence scheduling guess"}))
          actor (operation/build s {:advisor low-confidence-advisor})
          result (exec actor "t-lowconf" {:op :schedule-field-operation :subject "order-007"})]
      (is (= :interrupted (:status result)))
      (is (= [:request-approval] (:frontier result)))
      (is (empty? (store/ledger s)) "escalated, not committed -- nothing written yet")
      (is (nil? (:scheduled? (store/service-order (store/current s) "order-007")))))))

(deftest advisor-payload-swap-changes-committed-ledger-record
  (testing "a swapped-in Advisor's OWN :summary/:cites/:proposal payload --
            not MockAdvisor's canned string -- lands verbatim in the
            committed ledger fact, AND its declared :cost-usd (not a
            hardcoded threshold check) determines whether an
            :order-supplies proposal auto-commits or escalates"
    (let [distinctive-summary "procure replacement chainsaw chain stock"
          low-cost-advisor
          (reify advisor/Advisor
            (advise [_ request _st]
              {:op (:op request) :subject (:subject request) :effect :propose
               :cites [{:spec :jp/maff}] :confidence 0.95
               :summary distinctive-summary
               :value {:jurisdiction :jp/maff :cost-usd 1000}}))
          s (store/mem-store {"order-008" clean-fire-support-order})
          actor (operation/build s {:advisor low-cost-advisor})
          result (exec actor "t-supply-low" {:op :order-supplies :subject "order-008"})]
      (is (= :commit (:decision (:state result))))
      (let [committed (first (store/ledger s))]
        (is (= distinctive-summary (:summary committed)))
        (is (= 1000 (get-in committed [:proposal :value :cost-usd]))))))

  (testing "the SAME advisor swapped to declare a cost ABOVE the threshold
            escalates instead of committing -- same op, different advisor
            output, different real outcome"
    (let [high-cost-advisor
          (reify advisor/Advisor
            (advise [_ request _st]
              {:op (:op request) :subject (:subject request) :effect :propose
               :cites [{:spec :jp/maff}] :confidence 0.95
               :summary "procure replacement harvester"
               :value {:jurisdiction :jp/maff :cost-usd 10000}}))
          s (store/mem-store {"order-009" clean-fire-support-order})
          actor (operation/build s {:advisor high-cost-advisor})
          result (exec actor "t-supply-high" {:op :order-supplies :subject "order-009"})]
      (is (= :interrupted (:status result)))
      (is (= [:request-approval] (:frontier result)))
      (is (empty? (store/ledger s))))))
