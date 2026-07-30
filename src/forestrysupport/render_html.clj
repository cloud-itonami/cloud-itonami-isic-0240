(ns forestrysupport.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5). Drives the REAL actor stack (`forestrysupport.operation` ->
  `forestrysupport.governor` -> `forestrysupport.store`) through a scenario
  adapted from this repo's own `forestrysupport.sim` demo driver, rendered
  deterministically -- no invented numbers/ids/ops. Uses FIXED date strings
  (not the sim's ten-days-from-now/ago helpers) for byte-identical reruns.

  Usage: `clojure -M:dev:render-html [out-file]`."
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
  "A registered, fully-compliant fire-support service order with FIXED
  dates (deterministic -- the sim's ten-days-from-now/ago would make the
  page non-reproducible across reruns)."
  {:service-type :fire-support/patrol-and-fuelbreak-maintenance
   :jurisdiction :jp/maff
   :client-forest-operator-id "forest-42"
   :operator-certification-expiry-date "2026-08-09"
   :equipment-last-inspection-date "2026-07-20"
   :wind-speed-kmh 10.0
   :riparian-buffer-actual-m 20.0
   :evidence-checklist [:service-order-record :forest-boundary-map :field-log
                        :operator-certification :equipment-inspection-record
                        :riparian-buffer-assessment]})

(defn run-demo!
  "Seeds four service orders then runs every disposition: a clean
  schedule-field-operation (auto-commit), a service-record log (always
  escalates -- dispatcher approves), another log (escalates -- dispatcher
  REJECTS), and an out-of-allowlist equipment operation (HARD block).
  Every id/op is from forestrysupport.sim / governor / store."
  []
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

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger oid]
  (last (filter #(= (:subject %) oid) ledger)))

(defn- status-cell [ledger oid]
  (let [f (last-fact-for ledger oid)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"critical\">rejected (hold)</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :basis first)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ["        <tr><td><code>:schedule-field-operation</code></td><td><span class=\"ok\">auto-commit when clean (not high-stakes)</span></td></tr>"
   "        <tr><td><code>:log-service-record</code></td><td><span class=\"warn\">ALWAYS human approval (service-record logging)</span></td></tr>"
   "        <tr><td><code>:operate-chainsaw</code></td><td><span class=\"critical\">HARD out-of-allowlist -- direct equipment operation is never coordinated</span></td></tr>"])

(defn render [db]
  (let [cur (store/current db)
        ledger (vec (store/ledger db))
        order-ids ["order-001" "order-002" "order-003" "order-004"]
        order-row (fn [oid]
                    (let [o (store/service-order cur oid)]
                      (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
                              (esc oid) (esc (str (or (:service-type o) :n-a)))
                              (esc (str (or (:jurisdiction o) :n-a)))
                              (esc (or (:client-forest-operator-id o) "—"))
                              (status-cell ledger oid))))
        order-rows (str/join "\n" (map order-row order-ids))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-0240 &middot; forestry support ops</title><style>"
     "body{font:14px/1.5 -apple-system,system-ui,sans-serif;margin:0;color:#1a1a1a;background:#f5f5f5}"
     ".bar{background:#1a3a1a;color:#fff;padding:1.2rem 2rem}.bar h1{margin:0;font-size:1.15rem;font-weight:600}"
     ".badge{display:inline-block;margin-top:.4rem;font-size:.75rem;opacity:.8}"
     "main{max-width:980px;margin:1.5rem auto;padding:0 1rem}"
     ".card{background:#fff;border-radius:8px;padding:1.2rem 1.4rem;margin-bottom:1.2rem;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
     ".card h2{margin-top:0;font-size:1rem}.muted{color:#777;font-size:.82rem}"
     "table{border-collapse:collapse;width:100%;font-size:.85rem}th,td{text-align:left;padding:.42rem .5rem;border-bottom:1px solid #eee}th{font-weight:600;color:#555}"
     ".ok{color:#0a7d33}.warn{color:#9a6700}.critical{color:#b41010;font-weight:600}code{background:#f0f0f0;padding:.1rem .3rem;border-radius:3px;font-size:.8rem}"
     "</style></head><body>\n"
     "<header class=\"bar\">\n  <h1>Forestry support-service ops (ISIC 0240) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · service-record logging always human-approved · equipment operation permanently blocked</span>\n</header>\n"
     "<main>\n  <section class=\"card\">\n    <h2>Scenario service orders</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>forestrysupport.store</code> via <code>forestrysupport.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly. No invented data; dates fixed for deterministic reruns.</p>\n"
     "    <table>\n      <thead><tr><th>Order</th><th>Service type</th><th>Jurisdiction</th><th>Client operator</th><th>Last op status</th></tr></thead>\n      <tbody>\n"
     order-rows "\n      </tbody>\n    </table>\n  </section>\n"
     "  <section class=\"card\">\n    <h2>Action gate (ForestrySupport Governor)</h2>\n"
     "    <p class=\"muted\">HARD blocks cannot be overridden. Direct equipment operation (chainsaw etc.) is never coordinated -- only scheduling and service-record logging.</p>\n"
     "    <table>\n      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n      </tbody>\n    </table>\n  </section>\n"
     "  <section class=\"card\">\n    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n      <tbody>\n"
     ledger-rows "\n      </tbody>\n    </table>\n  </section>\n"
     "</main>\n</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        out-file (java.io.File. out)]
    (.. out-file getParentFile mkdirs)
    (spit out-file (render db))
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts )")))
