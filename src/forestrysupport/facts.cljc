(ns forestrysupport.facts
  "Reference facts for contract forestry-support-service providers:
  service-type safety windows (operator-certification currency,
  equipment-inspection currency, fieldwork wind-speed ceiling, riparian
  buffer minimum, in-forest haul-road load limit), jurisdiction
  evidence-checklist requirements. This namespace contains pure lookup
  functions for forestry-support-service safety compliance checks -- the
  Governor calls these to independently validate proposals; the advisor's
  confidence is never sufficient on its own.

  A forestry-support-service contractor (ISIC Rev.5 0240, Support services
  to forestry) performs CONTRACT LOGGING-SUPPORT SERVICES for other forest
  operators on a fee/contract basis -- timber cruising (forest-resource
  survey), forest-fire-protection support (patrol / fuel-break maintenance
  support, NOT firefighting/suppression itself), seed collection, and
  transport of logs within the forest -- WITHOUT itself felling timber or
  owning the standing timber being serviced. This is what distinguishes
  0240 from the actual timber-harvesting divisions (022x) themselves: the
  client forest operator's stand/harvest is never owned by this actor's
  operator, only the SUPPORT SERVICE performed around it.

  Service types split into two safety shapes:
    - Survey/collection services (timber cruising, seed collection) have
      NO field-equipment-operation safety window at all --
      operator-certification / equipment-inspection-interval /
      max-wind-speed / min-riparian-buffer / max-load are all nil for
      these, and the Governor's corresponding checks are skipped entirely
      rather than fabricating a target.
    - Field-equipment-operation services (forest-fire-protection support,
      in-forest log transport) carry a genuine operator-certification
      requirement (chainsaw/skidder/patrol-vehicle operator ticket),
      equipment-inspection interval (pre-use safety inspection currency,
      the interval itself varies genuinely by equipment class), and --
      where physically applicable to that specific service -- a maximum
      safe fieldwork wind speed (falling-limb/rollover hazard), a minimum
      riparian buffer (forestry BMP: Streamside Management Zone /
      erosion-and-sediment control), and a maximum haul-road load (in-
      forest transport rollover/road-damage hazard). Not every
      field-equipment-operation service type carries every one of these
      three physical-constraint fields -- e.g. in-forest log transport has
      no fieldwork wind-speed ceiling of its own (haul-road travel is not
      wind-sensitive the way canopy-adjacent chainsaw fuel-break work is),
      and fire-protection-support patrol/fuel-break work carries no
      haul-road load limit (it hauls no logs) -- the Governor's
      corresponding check is skipped entirely, never fabricated, when a
      given service type's field is nil."
  (:require [clojure.set :as set]))

(def service-types
  "Valid forestry-support-service categories and their safety windows.
  `equipment-inspection-interval-days`/`max-wind-speed-kmh`/
  `min-riparian-buffer-m`/`max-load-kg` are nil wherever a given
  physical-constraint check does not genuinely apply to that service type
  -- the Governor's corresponding check is skipped entirely for those
  fields rather than fabricating a target."
  {:survey/timber-cruising
   {:id :survey/timber-cruising
    :name "森林資源調査 (timber cruising)"
    :field-equipment-operation? false
    :equipment-inspection-interval-days nil
    :max-wind-speed-kmh nil
    :min-riparian-buffer-m nil
    :max-load-kg nil}

   :survey/seed-collection
   {:id :survey/seed-collection
    :name "採種 (種子採取)"
    :field-equipment-operation? false
    :equipment-inspection-interval-days nil
    :max-wind-speed-kmh nil
    :min-riparian-buffer-m nil
    :max-load-kg nil}

   :fire-support/patrol-and-fuelbreak-maintenance
   {:id :fire-support/patrol-and-fuelbreak-maintenance
    :name "山火事予防支援(巡回・防火帯整備支援)"
    :field-equipment-operation? true
    :equipment-inspection-interval-days 30
    :max-wind-speed-kmh 40.0
    :min-riparian-buffer-m 10.0
    :max-load-kg nil}

   :transport/in-forest-log-haul
   {:id :transport/in-forest-log-haul
    :name "林内集材・運材(林地内での丸太運搬)"
    :field-equipment-operation? true
    :equipment-inspection-interval-days 60
    :max-wind-speed-kmh nil
    :min-riparian-buffer-m 15.0
    :max-load-kg 20000.0}})

(defn service-type-by-id [id]
  (get service-types id))

(def jurisdictions
  "Forestry-support-service jurisdictions and their evidence-checklist
  requirements."
  {:jp/maff
   {:id :jp/maff
    :name "日本 (森林法・林野庁)"
    :required-evidence
    [:service-order-record
     :forest-boundary-map
     :field-log
     :operator-certification
     :equipment-inspection-record
     :riparian-buffer-assessment]}

   :us/state-forest-practices-act
   {:id :us/state-forest-practices-act
    :name "United States (State Forest Practices Act, e.g. Oregon/Washington)"
    :required-evidence
    [:service-order-record
     :forest-boundary-map
     :field-log
     :operator-certification
     :equipment-inspection-record
     :riparian-buffer-assessment]}

   :ca/bc-frpa
   {:id :ca/bc-frpa
    :name "Canada -- British Columbia (Forest and Range Practices Act)"
    :required-evidence
    [:service-order-record
     :forest-boundary-map
     :field-log
     :operator-certification
     :equipment-inspection-record
     :riparian-buffer-assessment]}})

(defn jurisdiction-by-id [id]
  (get jurisdictions id))

(defn required-evidence-satisfied?
  "Verify that every item in the jurisdiction's `:required-evidence` list
  is present in `evidence`. `jurisdiction` may be a resolved jurisdiction
  map (as returned by `jurisdiction-by-id`) or a raw jurisdiction id --
  both call conventions are in use (tests pass a resolved map; the
  Governor passes the raw id straight off service-order metadata)."
  [jurisdiction evidence]
  (let [j (if (map? jurisdiction) jurisdiction (jurisdiction-by-id jurisdiction))]
    (if-not j
      false
      (set/subset? (set (:required-evidence j)) (set evidence)))))

(defn operator-certification-current?
  "Positive-sense convenience predicate: is the field-equipment operator's
  certification valid (not yet expired) as of `now-epoch-ms`? Returns
  false when the service type has no field-equipment-operation
  certification requirement at all -- there is nothing to be 'current'
  about for a survey/collection service."
  [expiry-epoch-ms now-epoch-ms service-type]
  (boolean
   (and (some? service-type)
        (true? (:field-equipment-operation? service-type))
        (some? expiry-epoch-ms)
        (>= expiry-epoch-ms now-epoch-ms))))

(defn equipment-inspection-current?
  "Positive-sense convenience predicate: was the field equipment
  inspected within the service type's own safety-inspection interval of
  `now-epoch-ms`? Returns false when the service type has no
  equipment-inspection-interval spec at all."
  [last-inspection-epoch-ms now-epoch-ms service-type]
  (boolean
   (and (some? service-type)
        (true? (:field-equipment-operation? service-type))
        (some? (:equipment-inspection-interval-days service-type))
        (some? last-inspection-epoch-ms)
        (<= (- now-epoch-ms last-inspection-epoch-ms)
            (* (:equipment-inspection-interval-days service-type) 24 60 60 1000)))))

(defn wind-speed-in-range?
  "Positive-sense convenience predicate: does `actual-kmh` stay at or
  below the service type's maximum safe fieldwork wind speed
  (falling-limb/rollover hazard)? Returns false when the service type has
  no wind-speed ceiling at all."
  [actual-kmh service-type]
  (boolean
   (and (some? service-type)
        (some? (:max-wind-speed-kmh service-type))
        (some? actual-kmh)
        (<= actual-kmh (:max-wind-speed-kmh service-type)))))

(defn riparian-buffer-in-range?
  "Positive-sense convenience predicate: does `actual-m` meet or exceed
  the service type's minimum riparian buffer distance (forestry BMP
  Streamside Management Zone / erosion-and-sediment control)? Returns
  false when the service type has no riparian-buffer minimum at all."
  [actual-m service-type]
  (boolean
   (and (some? service-type)
        (some? (:min-riparian-buffer-m service-type))
        (some? actual-m)
        (>= actual-m (:min-riparian-buffer-m service-type)))))

(defn load-in-range?
  "Positive-sense convenience predicate: does `actual-kg` stay at or
  below the service type's maximum in-forest haul-road load (rollover /
  road-damage hazard)? Returns false when the service type has no load
  limit at all."
  [actual-kg service-type]
  (boolean
   (and (some? service-type)
        (some? (:max-load-kg service-type))
        (some? actual-kg)
        (<= actual-kg (:max-load-kg service-type)))))
