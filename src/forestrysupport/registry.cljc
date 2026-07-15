(ns forestrysupport.registry
  "Pure validation functions for forestry-support-service safety
  parameters. These are called by the Governor to independently verify
  physical/regulatory constraints -- the advisor's confidence is NOT
  sufficient to override these checks.

  All functions here are pure arithmetic/boolean predicates with no
  host-clock or I/O calls, so this namespace stays trivially portable
  across Clojure/ClojureScript. Callers that need the current time (see
  `operator-certification-expired?` / `equipment-inspection-overdue?`)
  obtain it themselves via a `:clj`/`:cljs` reader-conditional at the
  call site (see `forestrysupport.governor`).")

(defn operator-certification-expired?
  "Independently verify that the field-equipment operator's certification
  (chainsaw/skidder/patrol-vehicle operator ticket) has NOT expired as of
  `now-epoch-ms`. An expired certification means the person who performed
  a field-equipment-operation service was not certified to do so -- a
  genuine regulatory hazard distinct from any equipment or weather
  concern."
  [expiry-epoch-ms now-epoch-ms]
  (< expiry-epoch-ms now-epoch-ms))

(defn equipment-inspection-overdue?
  "Independently verify that the field equipment (chainsaw / skidder /
  patrol vehicle) was inspected within `interval-days` of `now-epoch-ms`.
  `last-inspection-epoch-ms` and `now-epoch-ms` are both epoch
  milliseconds -- callers obtain `now` via a `:clj`/`:cljs`
  reader-conditional, keeping this namespace free of any host-clock call.
  Uninspected field equipment risks both mechanical failure and unsafe
  operation."
  [last-inspection-epoch-ms now-epoch-ms interval-days]
  (> (- now-epoch-ms last-inspection-epoch-ms)
     (* interval-days 24 60 60 1000)))

(defn wind-speed-exceeded?
  "Independently verify that the actual wind speed at time of fieldwork
  did not exceed the service type's maximum safe wind speed. Excess wind
  during canopy-adjacent chainsaw/fuel-break work is a genuine
  falling-limb/rollover hazard."
  [actual-kmh max-kmh]
  (> actual-kmh max-kmh))

(defn riparian-buffer-violated?
  "Independently verify that the actual distance maintained from the
  nearest stream/water body met or exceeded the service type's minimum
  riparian buffer. A buffer that is too narrow risks sediment/erosion
  reaching the water body -- a standard forestry Streamside Management
  Zone best-management-practice concern."
  [actual-m min-m]
  (< actual-m min-m))

(defn load-limit-exceeded?
  "Independently verify that the actual in-forest haul-road load did not
  exceed the service type's maximum. Excess load on a forest haul road is
  a genuine rollover and road-damage hazard."
  [actual-kg max-kg]
  (> actual-kg max-kg))
