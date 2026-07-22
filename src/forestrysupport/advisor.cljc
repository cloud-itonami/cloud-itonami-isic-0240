(ns forestrysupport.advisor
  "ForestrySupportAdvisor -- the forestry-support-service scheduling/
  logging/procurement advisor.

  FIX: before this, this namespace was a docstring-only skeleton with NO
  protocol and NO function -- comments claiming 'in production, this is
  driven by langgraph-clj StateGraph with LLM chat turns' while defining
  nothing at all. It was never `:require`d by `forestrysupport.operation`
  or anything else in `src/` -- completely dead code, exactly the
  'Advisor exists but the real flow doesn't route through it' bug this
  fix closes. `forestrysupport.operation/build`'s compiled StateGraph now
  calls `advise` from its `:advise` node for every real actor run.

  This is a mock advisor (deterministic) that also serves as a seam for
  a real LLM via langchain.model (see `llm-advisor`). It reads the
  service order's own already-registered `:jurisdiction` off the store
  (never invents one) so its proposals carry a real jurisdiction
  citation for `forestrysupport.governor`'s `spec-basis-violations`
  check, and it covers both the happy path (legitimate service
  scheduling/logging/health-flagging/procurement) and the governor's
  hard-check failure modes (unregistered service order, out-of-allowlist
  op) so the actor can be tested end-to-end offline without LLM calls.

  The advisor operates purely at the proposal level; the Governor
  (`forestrysupport.governor`) independently validates every proposal
  against physical/safety/regulatory rules read straight off the store
  before anything commits -- the advisor's own confidence and citations
  are NEVER sufficient on their own (see `forestrysupport.governor`'s
  own docstring: the LLM has no notion of certification/inspection/
  wind/buffer/load currency, only the Governor re-derives those from
  the store's ground truth)."
  (:require [forestrysupport.store :as store]))

(defprotocol Advisor
  (advise [a request st]
    "st -- the plain-map store value (`forestrysupport.store/current`),
    used only to read the service order's own registered fields (e.g.
    `:jurisdiction`) for citation purposes -- never to pre-empt the
    Governor's own independent checks."))

(defrecord MockAdvisor []
  Advisor
  (advise [_ request st]
    (let [op           (:op request)
          subject      (:subject request)
          order        (store/service-order st subject)
          jurisdiction (:jurisdiction order)
          cites        (if jurisdiction [{:spec jurisdiction}] [])
          value        (cond-> {} jurisdiction (assoc :jurisdiction jurisdiction))]
      (case op
        :log-service-record
        {:op         :log-service-record
         :subject    subject
         :summary    "Log completed forestry-support-service record"
         :rationale  "Field service completed; evidence checklist and safety parameters on file"
         :cites      cites
         :confidence 0.9
         :effect     :propose
         :value      value}

        :schedule-field-operation
        {:op         :schedule-field-operation
         :subject    subject
         :summary    "Schedule forestry-support-service field operation"
         :rationale  "Client forest operator service-order intake complete"
         :cites      cites
         :confidence 0.9
         :effect     :propose
         :value      value}

        :flag-forest-health-concern
        {:op         :flag-forest-health-concern
         :subject    subject
         :summary    "Flag forest-health concern"
         :rationale  "Suspected pest/disease/fire-risk observed during fieldwork"
         :cites      cites
         :confidence 0.85
         :effect     :propose
         :value      value}

        :order-supplies
        {:op         :order-supplies
         :subject    subject
         :summary    "Order forestry-support-service equipment/fuel supplies"
         :rationale  "Fieldwork consumables/equipment procurement"
         :cites      cites
         :confidence 0.85
         :effect     :propose
         :value      (assoc value :cost-usd 1000)}

        ;; Fallback for an unknown/out-of-allowlist op -- e.g. a covert
        ;; direct forestry-equipment-operation request. Deliberately low
        ;; confidence and NO citation: `forestrysupport.governor`'s
        ;; `op-not-allowed-violations` hard check rejects this
        ;; unconditionally regardless of what the advisor proposes.
        {:op         op
         :subject    subject
         :summary    "Unknown or out-of-allowlist operation"
         :rationale  ""
         :cites      []
         :confidence 0.0
         :effect     :propose
         :value      {}}))))

(defn mock-advisor [] (->MockAdvisor))

;; LLM seam (stub for now, langchain integration point)
(defn llm-advisor [_model-name]
  ;; TODO: Implement real LLM advisor via langchain.model
  ;; For now, return the mock
  (mock-advisor))
