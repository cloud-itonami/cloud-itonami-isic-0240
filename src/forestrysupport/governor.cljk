(ns forestrysupport.governor
  "Forestry-Support Operations Governor -- the independent compliance
  layer that earns the ForestrySupportAdvisor the right to commit. The
  LLM has no notion of:
    - Whether a service order has been independently verified/registered
      in the store at all, for ANY of this actor's four proposal ops
    - Whether the field-equipment operator's certification (chainsaw/
      skidder/patrol-vehicle operator ticket) is current (only meaningful
      for field-equipment-operation service types)
    - Whether the field equipment's safety inspection is current (only
      meaningful for field-equipment-operation service types)
    - Whether the actual wind speed at time of fieldwork exceeded the
      service type's maximum safe wind speed (only meaningful where a
      wind-speed ceiling genuinely applies to that service type)
    - Whether the actual riparian-buffer distance maintained met the
      service type's minimum (only meaningful where a riparian-buffer
      minimum genuinely applies to that service type)
    - Whether the actual in-forest haul-road load exceeded the service
      type's maximum (only meaningful for in-forest log transport)
    - Whether a proposal is covertly requesting direct forestry-equipment
      control or a final fire-suppression-tactic decision
    - Whether a previously-raised forest-health concern has been resolved
    - Whether a service order has already been logged (double-commit)

  This MUST be a separate system able to *reject* a proposal and fall back
  to HOLD.

  Unlike direct forestry-equipment operation (chainsaw, skidder, patrol
  vehicle -- NEVER done by this actor) or finalizing a
  fire-suppression-tactic decision (NEVER done by this actor -- both are
  HARD, permanent governor blocks, never overridable by human approval),
  the Governor operates on service-order metadata: client-forest-operator
  identity, service parameters, and safety/compliance flags. This is
  forestry-SUPPORT-SERVICE OPERATIONS COORDINATION, not direct
  forestry-equipment operation authority or firefighting-tactics
  authority.

  CRITICAL: `:flag-forest-health-concern` ALWAYS escalates to human
  sign-off at every phase, regardless of advisor confidence -- a
  pest/disease/fire-risk concern is never auto-resolved by advisor
  confidence alone.

  Hard violations (always HOLD, no override):
    1. Operation outside the closed allowlist (`:op-not-allowed`) --
       includes any proposal that would amount to direct
       forestry-equipment control
    2. Proposal asserting an `:effect` other than `:propose`
       (`:effect-not-propose`)
    3. Service order not independently verified/registered in the store
       -- applies to ALL FOUR allowed ops (`:service-order-not-registered`)
    4. No jurisdiction citation (`:no-spec-basis`)
    5. Evidence checklist incomplete (`:evidence-incomplete`)
    6. Field-equipment operator certification expired
       (`:operator-certification-expired` -- only when the service type
       is a field-equipment-operation service)
    7. Field-equipment safety inspection overdue
       (`:equipment-inspection-overdue` -- only when the service type is
       a field-equipment-operation service)
    8. Wind speed exceeded the safe fieldwork ceiling
       (`:wind-speed-exceeded` -- only when the service type has a
       wind-speed ceiling)
    9. Riparian buffer narrower than the service type's minimum
       (`:riparian-buffer-violated` -- only when the service type has a
       riparian-buffer minimum)
   10. Haul-road load exceeded the service type's maximum
       (`:load-limit-exceeded` -- only when the service type has a load
       limit)
   11. Proposal covertly requests direct forestry-equipment control or a
       final fire-suppression-tactic decision
       (`:forestry-equipment-or-fire-tactic-decision-blocked` -- a HARD,
       PERMANENT block, never overridable by human approval, evaluated
       against every op as defense-in-depth even though those actions are
       already outside the closed allowlist)
   12. Unresolved forest-health concern (`:forest-health-flag-unresolved`)
   13. Service order already logged (`:already-logged`, double-commit
       guard)

  Soft gates (always escalate for human):
    - Low confidence
    - `:log-service-record` -- the one real actuation event this actor
      performs (logging completed, billable field work into records)
    - `:flag-forest-health-concern` -- never auto-resolved by confidence
      alone
    - `:order-supplies` above the cost threshold
      (`supply-order-cost-threshold-usd`)

  This design mirrors `cropsupport.governor` (ISIC 0161, support
  activities for crop production) in overall shape but specializes on
  forestry-SUPPORT-SERVICE safety concerns -- field-equipment-operator
  certification, equipment-inspection currency, fieldwork wind ceiling,
  riparian buffer, and haul-road load -- for contract logging-support work
  performed for OTHER forest operators, never timber the operator itself
  fells or owns."
  (:require [forestrysupport.facts :as facts]
            [forestrysupport.registry :as registry]
            [forestrysupport.store :as store]))

(def confidence-floor 0.6)

(def supply-order-cost-threshold-usd
  "Supply orders (equipment/fuel procurement) at or below this cost may
  auto-commit when the Governor is otherwise clean; orders above this
  threshold always require human sign-off, regardless of advisor
  confidence."
  5000)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Logging a completed service record (`:log-service-record`) is the one
  real-world actuation event this actor performs -- it commits billable
  field-work data (and, transitively, the safety/compliance facts that
  accompanied it) into the permanent record."
  #{:log-service-record})

(def always-escalate-ops
  "Operations that always require human sign-off, even when the
  Governor's hard checks are clean and confidence is high: the high-
  stakes actuation event (`high-stakes`) plus
  `:flag-forest-health-concern` -- a forest-health concern (pest,
  disease, fire risk) is never auto-resolved by advisor confidence
  alone, it always needs a human look."
  (conj high-stakes :flag-forest-health-concern))

(def allowed-ops
  "Closed allowlist of proposal operations this actor may ever make. Any
  proposal for an operation outside this set -- most importantly direct
  forestry-equipment operation (chainsaw, skidder, patrol vehicle) or a
  final fire-suppression-tactic decision -- is a hard, permanent block:
  this actor coordinates forestry-support-service operations, it does
  not operate forestry equipment and it does not fight fires."
  #{:log-service-record :schedule-field-operation :flag-forest-health-concern :order-supplies})

;; ────────────────────────── Checks ──────────────────────────

(defn- op-not-allowed-violations
  "HARD, permanent block: any proposal outside the closed operation
  allowlist (e.g. direct forestry-equipment operation) is refused
  unconditionally -- this actor has no authority to make such a proposal
  at all, let alone commit it."
  [{:keys [op]} _proposal]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowed
      :detail (str op " はこのactorの許可された提案種別 (log-service-record/"
                  "schedule-field-operation/flag-forest-health-concern/order-supplies) "
                  "に含まれない -- 林業機材の直接操作はこのactorに無い")}]))

(defn- effect-not-propose-violations
  "HARD invariant: this actor's proposals are always `:effect :propose` --
  it never claims direct write/actuation authority for itself. A proposal
  asserting any other effect is refused unconditionally."
  [_request proposal]
  (when-let [effect (:effect proposal)]
    (when (not= effect :propose)
      [{:rule :effect-not-propose
        :detail (str "この actor の提案は :propose 以外の :effect を持てない (got " effect ")")}])))

(defn- service-order-not-registered-violations
  "HARD invariant: a service-order/client-forest-operator record must be
  independently verified/registered in the store BEFORE any of this
  actor's four proposal ops can be made against it -- coordinating work
  for an engagement this actor never checked in is out of scope.
  Evaluated across ALL FOUR allowed ops, not just one."
  [{:keys [op subject]} st]
  (when (contains? allowed-ops op)
    (when-not (store/service-order-registered? st subject)
      [{:rule :service-order-not-registered
        :detail (str subject " は独立に検証・登録されたservice-order記録が無い -- いかなる提案も進められない")}])))

(defn- spec-basis-violations
  "A proposal with no jurisdiction citation is a HARD violation -- never
  invent a jurisdiction's forestry-support-service safety requirements."
  [{:keys [op]} proposal]
  (when (contains?
         #{:log-service-record :order-supplies :flag-forest-health-concern}
         op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :jurisdiction) (nil? (:jurisdiction value))))
        [{:rule :no-spec-basis
          :detail "公式仕様の引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:log-service-record`, verify the service order's evidence
  checklist is complete per jurisdiction requirements."
  [{:keys [op subject]} st]
  (when (= op :log-service-record)
    (let [o (store/service-order st subject)]
      (when-not (and o
                     (facts/required-evidence-satisfied?
                      (:jurisdiction o)
                      (:evidence-checklist o)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(service-order-record/forest-boundary-map/operator-certification等)が充足していない状態での提案"}]))))

(defn- operator-certification-expired-violations
  "For `:log-service-record`, INDEPENDENTLY verify the field-equipment
  operator's certification has not expired via
  `registry/operator-certification-expired?`. Only evaluated when the
  service type actually requires certification (field-equipment-
  operation service types) -- survey/collection service types have
  nothing to check here, never a fabricated requirement."
  [{:keys [op subject]} st now-ms]
  (when (= op :log-service-record)
    (let [o (store/service-order st subject)
          st* (when o (facts/service-type-by-id (:service-type o)))]
      (when (and o st* (:field-equipment-operation? st*) (:operator-certification-expiry-date o)
                 (registry/operator-certification-expired? (:operator-certification-expiry-date o) now-ms))
        [{:rule :operator-certification-expired
          :detail (str subject " の林業機材操作者資格(operator certification)が失効している -- 記録提案は進められない")}]))))

(defn- equipment-inspection-overdue-violations
  "For `:log-service-record`, INDEPENDENTLY verify the field equipment's
  safety inspection is current via `registry/equipment-inspection-overdue?`.
  Only evaluated when the service type actually requires inspection
  (field-equipment-operation service types)."
  [{:keys [op subject]} st now-ms]
  (when (= op :log-service-record)
    (let [o (store/service-order st subject)
          st* (when o (facts/service-type-by-id (:service-type o)))]
      (when (and o st* (:field-equipment-operation? st*)
                 (:equipment-inspection-interval-days st*)
                 (:equipment-last-inspection-date o)
                 (registry/equipment-inspection-overdue?
                  (:equipment-last-inspection-date o) now-ms
                  (:equipment-inspection-interval-days st*)))
        [{:rule :equipment-inspection-overdue
          :detail (str subject " の林業機材の安全点検が期限切れ -- 記録提案は進められない")}]))))

(defn- wind-speed-exceeded-violations
  "For `:log-service-record`, INDEPENDENTLY verify that the actual wind
  speed at time of fieldwork did not exceed the service type's maximum
  safe wind speed via `registry/wind-speed-exceeded?`. Only evaluated
  when the service type actually has a wind-speed ceiling."
  [{:keys [op subject]} st]
  (when (= op :log-service-record)
    (let [o (store/service-order st subject)
          st* (when o (facts/service-type-by-id (:service-type o)))]
      (when (and o st* (:max-wind-speed-kmh st*) (:wind-speed-kmh o)
                 (registry/wind-speed-exceeded?
                  (:wind-speed-kmh o)
                  (:max-wind-speed-kmh st*)))
        [{:rule :wind-speed-exceeded
          :detail (str subject " の作業時風速(" (:wind-speed-kmh o)
                      "km/h)が安全基準を超過 -- 記録提案は進められない")}]))))

(defn- riparian-buffer-violated-violations
  "For `:log-service-record`, INDEPENDENTLY verify that the actual
  distance maintained from the nearest stream/water body met the service
  type's minimum riparian buffer via `registry/riparian-buffer-violated?`.
  Only evaluated when the service type actually has a riparian-buffer
  minimum."
  [{:keys [op subject]} st]
  (when (= op :log-service-record)
    (let [o (store/service-order st subject)
          st* (when o (facts/service-type-by-id (:service-type o)))]
      (when (and o st* (:min-riparian-buffer-m st*) (:riparian-buffer-actual-m o)
                 (registry/riparian-buffer-violated?
                  (:riparian-buffer-actual-m o)
                  (:min-riparian-buffer-m st*)))
        [{:rule :riparian-buffer-violated
          :detail (str subject " の河畔緩衝帯距離(" (:riparian-buffer-actual-m o)
                      "m)が最小基準を下回る -- 記録提案は進められない")}]))))

(defn- load-limit-exceeded-violations
  "For `:log-service-record`, INDEPENDENTLY verify that the actual
  in-forest haul-road load did not exceed the service type's maximum via
  `registry/load-limit-exceeded?`. Only evaluated when the service type
  actually has a load limit (in-forest log transport)."
  [{:keys [op subject]} st]
  (when (= op :log-service-record)
    (let [o (store/service-order st subject)
          st* (when o (facts/service-type-by-id (:service-type o)))]
      (when (and o st* (:max-load-kg st*) (:load-actual-kg o)
                 (registry/load-limit-exceeded?
                  (:load-actual-kg o)
                  (:max-load-kg st*)))
        [{:rule :load-limit-exceeded
          :detail (str subject " の林内運材荷重(" (:load-actual-kg o)
                      "kg)が最大基準を超過 -- 記録提案は進められない")}]))))

(defn- forestry-equipment-or-fire-tactic-decision-blocked-violations
  "HARD, PERMANENT block, defense-in-depth: any proposal whose `:value`
  covertly requests direct forestry-equipment control
  (`:operate-forestry-equipment?` true) or a final
  fire-suppression-tactic decision (`:finalize-fire-suppression-tactic-decision?`
  true) is refused unconditionally, regardless of which op it is
  nominally filed under and regardless of advisor confidence. Never
  overridable by human approval -- this is a scope boundary, not a risk
  judgment."
  [_request proposal]
  (let [value (:value proposal)]
    (when (or (true? (:operate-forestry-equipment? value))
              (true? (:finalize-fire-suppression-tactic-decision? value)))
      [{:rule :forestry-equipment-or-fire-tactic-decision-blocked
        :detail "林業機材の直接操作または消火戦術の最終決定はこのactorの範囲外 -- 恒久的にブロックされる"}])))

(defn- forest-health-flag-unresolved-violations
  "An unresolved forest-health flag is a HARD, un-overridable hold.
  Forest-health concerns (suspected pest infestation, disease, fire risk)
  raised during service must be resolved before the service order can be
  logged. Evaluated UNCONDITIONALLY at `:log-service-record`."
  [{:keys [op subject]} st]
  (when (= op :log-service-record)
    (let [o (store/service-order st subject)]
      (when (and (true? (:forest-health-concern-raised? o))
                 (not (true? (:forest-health-concern-resolved? o))))
        [{:rule :forest-health-flag-unresolved
          :detail (str subject " は未解決の森林健全性フラグがある -- 記録提案は進められない")}]))))

(defn- already-logged-violations
  "For `:log-service-record`, refuse to log the SAME service order twice,
  off a dedicated `:logged?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :log-service-record)
    (when (store/service-order-already-logged? st subject)
      [{:rule :already-logged
        :detail (str subject " は既に記録済み")}])))

(defn- now-epoch-ms
  "Current time in epoch milliseconds, portable across Clojure/
  ClojureScript. Isolated to this single call site so the rest of the
  namespace (and all of `forestrysupport.registry`) stays free of
  host-clock calls."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (js/Date.now)))

(defn- high-cost-supply-order?
  "Soft-gate helper: a `:order-supplies` proposal escalates to a human
  unless its `:cost-usd` can be established to be BELOW `supply-order-cost-threshold-usd`.

  Note the direction. This gate used to read `:cost-usd` out of the
  advisor's OWN proposal and escalate only when that number exceeded
  the threshold, which made the gate's only input the very number it
  existed to doubt:

    - an advisor understating bought itself an auto-commit wherever
      `:order-supplies` was `:auto`-eligible -- no human saw it;
    - `(some-> amount (> threshold))` returned nil when the field was
      ABSENT, so omitting `:cost-usd` skipped the gate entirely.

  There is no filed catalog in this actor's store to recompute the
  figure from -- the advisor states it directly -- so a self-declared
  value cannot be verified. An unverifiable number is worthless as a
  DE-escalation signal: it may raise the alarm, it must never silence
  it. The gate now escalates whenever the value is absent, non-numeric,
  or above the threshold, and stands down only for one that is present,
  numeric and below it."
  [{:keys [op]} proposal]
  (when (= op :order-supplies)
    (let [v (get-in proposal [:value :cost-usd])]
      (or (not (number? v))
          (> v supply-order-cost-threshold-usd)))))

(defn check
  "Censors a ForestrySupportAdvisor proposal against the Governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}.

  Stakes (high-stakes actuation vs. always-escalate vs. high-cost supply
  order) are read off the REQUEST's `:op` (and, for supply-order cost,
  the proposal's own declared value) -- not off the advisor's self-
  reported stake -- since the operation being proposed is what determines
  whether a human must sign off."
  [request _context proposal st]
  (let [now-ms (now-epoch-ms)
        hard (into []
                   (concat (op-not-allowed-violations request proposal)
                           (effect-not-propose-violations request proposal)
                           (service-order-not-registered-violations request st)
                           (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (operator-certification-expired-violations request st now-ms)
                           (equipment-inspection-overdue-violations request st now-ms)
                           (wind-speed-exceeded-violations request st)
                           (riparian-buffer-violated-violations request st)
                           (load-limit-exceeded-violations request st)
                           (forestry-equipment-or-fire-tactic-decision-blocked-violations request proposal)
                           (forest-health-flag-unresolved-violations request st)
                           (already-logged-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        actuation? (boolean (high-stakes (:op request)))
        escalate-op? (or (boolean (always-escalate-ops (:op request)))
                          (boolean (high-cost-supply-order? request proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not escalate-op?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? escalate-op?))
     :high-stakes? actuation?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
