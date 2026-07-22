(ns forestrysupport.operation
  "OperationActor -- one forestry-support-service coordination request =
  one supervised actor run, expressed as a REAL compiled `langgraph-clj`
  `StateGraph` (`langgraph.graph/state-graph` + `compile-graph`). The
  advisor (`forestrysupport.advisor/Advisor`) is sealed into a single
  node (`:advise`); its proposal is ALWAYS routed through the independent
  `forestrysupport.governor/check` (`:govern`) before anything commits to
  the SSoT.

  FIX: this replaces the previous `run-operation`, a plain function that
  took an ALREADY-BUILT `proposal` map as an argument and threaded it
  through `governor-fn` exactly once. It never constructed a proposal via
  the Advisor at all -- `forestrysupport.advisor` was dead code, never
  `:require`d from any real call path -- and it never touched
  `langgraph.graph`/`state-graph`/`add-node`/`compile-graph`, despite this
  actor's own stated design (a sealed LLM advisor governed by an
  independent Governor, per `build-actor`/ADR-2607011000) requiring
  exactly that shape. `run-operation` itself was only ever called from
  `forestrysupport.operation-test` -- never from `forestrysupport.sim` or
  any other real driver.

  State machine:
  intake -> advise -> govern -> decide -+-> commit
                                         +-> request-approval -> commit
                                         +-> hold

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (`forestrysupport.store/mem-store`, or any `Store` impl)
    - the Advisor  (mock today; `forestrysupport.advisor/Advisor` is
                     already the injection point -- see its docstring)

  One graph run = one forestry-support-service coordination request. No
  unbounded inner loop -- each run is auditable and checkpointed. Every
  commit/hold/approval-rejected decision fact lands in
  `forestrysupport.store`'s append-only ledger (`store/append-ledger!`),
  reachable ONLY from the `:commit` and `:hold` terminal nodes of the
  compiled graph below -- before this fix, the pure functions
  `forestrysupport.store/append-fact`/`log-service-record`/
  `mark-scheduled` this wraps were called ONLY from
  `forestrysupport.store-test`, never from any real execution path, so
  no genuine audit trail (or SSoT mutation) was ever produced by a real
  operation.

  Human-in-the-loop = real approval workflow:
  `interrupt-before #{:request-approval}` pauses the actor at the
  `:request-approval` node until a human dispatcher/operator resumes it
  with a decision. `:log-service-record` and `:flag-forest-health-concern`
  ALWAYS reach this node when the Governor is clean -- see
  `forestrysupport.governor/always-escalate-ops`."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [forestrysupport.advisor :as advisor]
            [forestrysupport.governor :as governor]
            [forestrysupport.store :as store]))

;; ============================================================================
;; Audit-fact builders
;; ============================================================================

(defn- escalate-fact
  "The audit fact written when a proposal is routed to human sign-off --
  either because the Governor flagged low advisor confidence or because
  the op is always-escalate (`log-service-record`/
  `flag-forest-health-concern`) or a high-cost supply order."
  [request verdict]
  {:t            :approval-requested
   :op           (:op request)
   :subject      (:subject request)
   :confidence   (:confidence verdict)
   :high-stakes? (:high-stakes? verdict)})

(defn- commit-fact
  "The audit fact written when a proposal commits. `:proposal` carries
  the full advisor proposal (summary/rationale/citations/confidence/
  value) -- the ledger fact itself plus the SSoT mutation performed in
  the `:commit` node below (for the two ops that have one --
  `:log-service-record`/`:schedule-field-operation`) are the durable
  record of what happened."
  [request proposal approval]
  (cond-> {:t           :committed
           :op          (:op request)
           :subject     (:subject request)
           :disposition :commit
           :basis       (:cites proposal)
           :summary     (:summary proposal)
           :proposal    proposal}
    approval (assoc :approved-by (:by approval))))

;; ============================================================================
;; Compiled StateGraph
;; ============================================================================

(defn build
  "Compiles an OperationActor graph bound to `store` (a
  `forestrysupport.store/Store`, e.g. `(forestrysupport.store/mem-store
  seed-orders)`). opts:
    :advisor      -- a `forestrysupport.advisor/Advisor` (default: mock-advisor)
    :checkpointer -- a `langgraph.checkpoint/Checkpointer`
                     (default: in-memory `cp/mem-checkpointer`)

  The compiled graph's input map: `{:request .. :context ..}`. `:context`
  is per-request (default `{:actor-id :forestrysupport-actor}`) and is
  threaded straight into `forestrysupport.governor/check`/`hold-fact` --
  it carries `:actor-id`, matching `governor/hold-fact`'s own read."
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request  {:default nil}
         :context  {:default {:actor-id :forestrysupport-actor}}
         :proposal {:default nil}
         :verdict  {:default nil}
         :decision {:default nil}
         :approval {:default nil}
         :audit    {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      (g/add-node :advise
        (fn [{:keys [request]}]
          {:proposal (advisor/advise advisor request (store/current store))}))

      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal (store/current store))}))

      (g/add-node :decide
        (fn [{:keys [request context verdict]}]
          (cond
            ;; HARD governor violations are a permanent block -- NEVER
            ;; routed through human approval, straight to :hold.
            (:hard? verdict)
            {:decision :hold
             :audit    [(governor/hold-fact request context verdict)]}

            (:escalate? verdict)
            {:decision :escalate
             :audit    [(escalate-fact request verdict)]}

            :else
            {:decision :commit})))

      (g/add-node :request-approval
        (fn [{:keys [request context verdict approval]}]
          (if (= :approved (:status approval))
            {:decision :commit
             :audit    [{:t :approval-granted :op (:op request)
                         :subject (:subject request) :by (:by approval)}]}
            {:decision :hold
             :audit    [(assoc (governor/hold-fact request context verdict)
                               :t :approval-rejected)]})))

      (g/add-node :commit
        (fn [{:keys [request proposal approval]}]
          (let [subject (:subject request)
                order   (store/service-order (store/current store) subject)]
            (case (:op request)
              :log-service-record
              (store/commit-log-service-record! store subject (merge order (:value proposal)))

              :schedule-field-operation
              (store/commit-schedule! store subject)

              ;; :flag-forest-health-concern / :order-supplies have no
              ;; dedicated SSoT field to mutate today -- the ledger fact
              ;; below IS their durable record.
              nil))
          (let [f (commit-fact request proposal approval)]
            (store/append-ledger! store f)
            {:audit [f]})))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store hf))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [decision]}]
          (case decision
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [decision]}]
          (if (= :commit decision) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
