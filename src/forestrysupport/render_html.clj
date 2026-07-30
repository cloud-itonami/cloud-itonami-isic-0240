(ns forestrysupport.render-html
  "Build-time HTML renderer. Drives the REAL actor stack deterministically.
   Uses FIXED java.time.LocalDate dates (not the sim's relative helpers) for
   byte-identical reruns. Usage: clojure -M:dev:render-html [out-file]."
  (:require [clojure.string :as str]
            [forestrysupport.store :as store]
            [forestrysupport.operation :as op]
            [langgraph.graph :as g]))

(defn- exec! [actor tid request]
  (g/run* actor {:request request} {:thread-id tid}))
(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "dispatcher-01"}} {:thread-id tid :resume? true}))
(defn- reject!  [actor tid]
  (g/run* actor {:approval {:status :rejected :by "dispatcher-01"}} {:thread-id tid :resume? true}))

(def ^:private clean-order
  {:service-type :fire-support/patrol-and-fuelbreak-maintenance
   :jurisdiction :jp/maff
   :client-forest-operator-id "forest-42"
   :operator-certification-expiry-date (java.time.LocalDate/of 2026 8 9)
   :equipment-last-inspection-date (java.time.LocalDate/of 2026 7 20)
   :wind-speed-kmh 10.0
   :riparian-buffer-actual-m 20.0
   :evidence-checklist [:service-order-record :forest-boundary-map :field-log]})

(defn run-demo! []
  (let [db (store/mem-store {"order-001" clean-order "order-002" clean-order
                             "order-003" clean-order "order-004" clean-order})
        actor (op/build db)]
    (exec! actor "t1" {:op :schedule-field-operation :subject "order-001"})
    (exec! actor "t2" {:op :log-service-record :subject "order-002"})
    (approve! actor "t2")
    (exec! actor "t3" {:op :log-service-record :subject "order-003"})
    (reject! actor "t3")
    (exec! actor "t4" {:op :operate-chainsaw :subject "order-004"})
    db))

(defn- esc [v]
  (-> (str v) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger oid]
  (last (filter #(= (:subject %) oid) ledger)))

(defn- status-cell [ledger oid]
  (let [f (last-fact-for ledger oid)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved</span>"
      (= :approval-rejected (:t f)) "<span class=\"critical\">rejected</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :basis first)]
        (str "<span class=\"critical\">HARD hold: " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ["        <tr><td><code>:schedule-field-operation</code></td><td><span class=\"ok\">auto-commit when clean</span></td></tr>"
   "        <tr><td><code>:log-service-record</code></td><td><span class=\"warn\">ALWAYS human approval</span></td></tr>"
   "        <tr><td><code>:operate-chainsaw</code></td><td><span class=\"critical\">HARD out-of-allowlist</span></td></tr>"])

(defn render [db]
  (let [cur (store/current db)
        ledger (vec (store/ledger db))
        order-ids ["order-001" "order-002" "order-003" "order-004"]
        orow (fn [oid]
               (let [o (store/service-order cur oid)]
                 (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
                         (esc oid) (esc (str (or (:service-type o) :n-a)))
                         (esc (or (:client-forest-operator-id o) "-"))
                         (status-cell ledger oid))))
        order-rows (str/join "\n" (map orow order-ids))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-0240</title>"
     "<style>body{font:14px/1.5 sans-serif;margin:0;color:#1a1a1a;background:#f5f5f5}"
     ".bar{background:#1a3a1a;color:#fff;padding:1.2rem 2rem}.bar h1{margin:0;font-size:1.15rem}"
     "main{max-width:980px;margin:1.5rem auto;padding:0 1rem}"
     ".card{background:#fff;border-radius:8px;padding:1.2rem 1.4rem;margin-bottom:1.2rem;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
     ".muted{color:#777;font-size:.82rem}table{border-collapse:collapse;width:100%;font-size:.85rem}"
     "th,td{text-align:left;padding:.42rem .5rem;border-bottom:1px solid #eee}th{font-weight:600;color:#555}"
     ".ok{color:#0a7d33}.warn{color:#9a6700}.critical{color:#b41010;font-weight:600}"
     "code{background:#f0f0f0;padding:.1rem .3rem;border-radius:3px;font-size:.8rem}</style></head><body>"
     "<header class=\"bar\"><h1>Forestry support ops (ISIC 0240)</h1></header><main>"
     "<section class=\"card\"><h2>Service orders</h2>"
     "<table><thead><tr><th>Order</th><th>Service type</th><th>Client</th><th>Status</th></tr></thead><tbody>"
     order-rows "</tbody></table></section>"
     "<section class=\"card\"><h2>Action gate</h2>"
     "<table><thead><tr><th>Op</th><th>Gate</th></tr></thead><tbody>"
     (str/join "\n" action-gate-rows) "</tbody></table></section>"
     "<section class=\"card\"><h2>Audit ledger</h2>"
     "<table><thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead><tbody>"
     ledger-rows "</tbody></table></section>"
     "</main></body></html>")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!) out-file (java.io.File. out)]
    (.. out-file getParentFile mkdirs)
    (spit out-file (render db))
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts )")))
