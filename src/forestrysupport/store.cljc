(ns forestrysupport.store
  "Store abstraction for forestry-support-service orders. Current
  implementation operates on plain data (`{:service-orders
  {service-order-id order-map} :facts [...]}`); production should migrate
  this seam to Datomic/kotoba-server (the same seam point all cloud-itonami
  actors use) while keeping the same pure-function surface.

  A service order is the minimal unit of work: one timber-cruising/
  seed-collection/fire-protection-support/log-transport engagement
  performed for a CLIENT FOREST OPERATOR (never the operator's own stand
  -- that is the defining shape of ISIC 0240, support services to
  forestry). Representative service-order keys:
    - :service-type keyword service-type id (see `forestrysupport.facts/service-types`)
    - :jurisdiction keyword jurisdiction id (see `forestrysupport.facts/jurisdictions`)
    - :client-forest-operator-id the served forest operator's identifier
      (never the operator's own)
    - :stand-area-hectares serviced stand area
    - :evidence-checklist evidence items present for the service order
    - :operator-certification-expiry-date epoch-ms of the field-equipment
      operator's certification expiry (nil for survey/collection service
      types)
    - :equipment-last-inspection-date epoch-ms of last field-equipment
      safety inspection (nil for survey/collection service types)
    - :wind-speed-kmh actual wind speed at time of fieldwork (nil where
      the service type has no wind-speed ceiling)
    - :riparian-buffer-actual-m actual distance maintained from the
      nearest stream/water body (nil where the service type has no
      riparian-buffer minimum)
    - :load-actual-kg actual in-forest haul-road load (nil where the
      service type has no load limit)
    - :forest-health-concern-raised? / :forest-health-concern-resolved?
      open pest/disease/fire-risk concern flag
    - :logged? true once a `:log-service-record` proposal commits
    - :scheduled? true once a `:schedule-field-operation` proposal commits

  The ledger (`:facts`) is a separate append-only vector of audit facts,
  kept alongside `:service-orders` in the same store value.

  `MemStore` (below) is a thin atom wrapper around this plain-data value,
  composing the pure functions above -- it exists because
  `forestrysupport.operation/build`'s compiled StateGraph needs ONE
  mutable SSoT instance it can close over across graph nodes within a
  single actor run (and across a human-in-the-loop resume), the same
  role `transportops.store/MemStore` plays for the ISIC-869 sibling
  actor. Before this was added, `log-service-record`/`mark-scheduled`/
  `append-fact` were called ONLY from `forestrysupport.store-test` --
  no real execution path ever mutated a service order or appended to
  the audit ledger.")

(defn service-order
  "Retrieve a service order by id, or nil if it does not exist / is not
  yet registered."
  [st service-order-id]
  (get-in st [:service-orders service-order-id]))

(defn service-order-registered?
  "True only if the service order exists in the store -- registration is
  the HARD invariant that must be independently verified before ANY of
  this actor's four proposal ops can be made against it."
  [st service-order-id]
  (some? (service-order st service-order-id)))

(defn service-order-already-logged?
  "True only if the service order exists and has already been marked
  logged."
  [st service-order-id]
  (true? (:logged? (service-order st service-order-id))))

(defn log-service-record
  "Register/update `order-data` under `service-order-id` and mark it
  logged (one-way flag). Used once a `:log-service-record` proposal
  commits."
  [st service-order-id order-data]
  (assoc-in st [:service-orders service-order-id] (assoc order-data :logged? true)))

(defn mark-scheduled
  "Mark an existing service order as scheduled (one-way flag). Used once
  a `:schedule-field-operation` proposal commits."
  [st service-order-id]
  (assoc-in st [:service-orders service-order-id :scheduled?] true))

(defn audit-trail
  "Return the append-only audit ledger (empty vector if none yet)."
  [st]
  (get st :facts []))

(defn append-fact
  "Append `fact` to the store's audit ledger."
  [st fact]
  (update st :facts (fnil conj []) fact))

;; ----------------------------- Stateful actor-facing wrapper -----------------------------

(defprotocol Store
  (current [s]
    "Current plain-map store value (`{:service-orders .. :facts ..}`) --
    pass this to the pure read functions above / `forestrysupport.advisor`/
    `forestrysupport.governor`.")
  (commit-log-service-record! [s service-order-id order-data]
    "Apply a committed `:log-service-record` proposal -- delegates to
    `log-service-record` above.")
  (commit-schedule! [s service-order-id]
    "Apply a committed `:schedule-field-operation` proposal -- delegates
    to `mark-scheduled` above.")
  (ledger [s] "The append-only immutable decision-fact log.")
  (append-ledger! [s fact]
    "Append one immutable decision fact -- delegates to `append-fact`
    above. Returns `fact`."))

(defrecord MemStore [a]
  Store
  (current [_] @a)
  (commit-log-service-record! [_ service-order-id order-data]
    (swap! a log-service-record service-order-id order-data)
    nil)
  (commit-schedule! [_ service-order-id]
    (swap! a mark-scheduled service-order-id)
    nil)
  (ledger [_] (audit-trail @a))
  (append-ledger! [_ fact]
    (swap! a append-fact fact)
    fact))

(defn mem-store
  "A `MemStore` seeded with an explicit `service-orders` map
  (service-order-id string -> service-order map). `service-orders` may
  be empty (an unregistered-everywhere store) -- the deterministic
  default for dev/tests/demo (no external deps)."
  ([] (mem-store {}))
  ([service-orders]
   (->MemStore (atom {:service-orders service-orders :facts []}))))
