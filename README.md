# cloud-itonami-isic-0240: Support Services To Forestry Coordination Actor

**ISIC Rev. 5 0240** — Support services to forestry

A distributed actor for autonomous, compliant coordination of contract forestry-support-service operations: service-order intake → stand/forest-condition survey → scheduling advice → timber-cruising/fire-protection-support/seed-collection/log-transport field work → service-record logging → compliance audit. Sealed LLM advisor; independent Governor enforcement; append-only audit ledger. **Not forestry-equipment operation.** Chainsaw/skidder/patrol-vehicle operation remains exclusive to licensed field-equipment operators, and this actor never finalizes a fire-suppression-tactic decision on its own.

## Scope

This actor coordinates **contract logging-support operations** performed for OTHER forest operators on a fee/contract basis — the operator never fells or owns the standing timber being serviced, which is what distinguishes ISIC 0240 from the actual timber-harvesting divisions (022x) themselves:

- Service-record logging (timber-cruising/seed-collection/fire-protection-support/log-transport service-hours data, safety/compliance parameters)
- Timber-cruising/fire-protection-support/seed-collection/log-transport service scheduling proposals
- Forest-health concern escalation (pest/disease/fire-risk, always escalates)
- Equipment/fuel procurement proposals

**Out of scope:**
- Direct forestry-equipment operation (chainsaw, skidder, patrol vehicle — exclusive to licensed field-equipment operators)
- Finalizing a fire-suppression-tactic decision (permanent, un-overridable governor block)
- Felling or otherwise harvesting the timber itself (that is ISIC 022x, the timber-harvesting divisions)
- Regulatory interpretation (proposals cite jurisdiction specifications; the Governor enforces only published requirements)

## Design

### Governor (Independent Compliance Layer)

The Governor is the separation-of-powers enforcement. It never trusts the advisor's confidence for anything safety- or compliance-relevant, and it always wins over the advisor.

- **Hard HOLD** (un-overridable):
  - Operation outside the closed allowlist (`:op-not-allowed`) — includes any proposal that would amount to direct forestry-equipment control
  - Proposal asserting an `:effect` other than `:propose` (`:effect-not-propose`)
  - Service order not independently verified/registered in the store — applies to ALL FOUR allowed ops (`:service-order-not-registered`)
  - No jurisdiction citation (`:no-spec-basis`)
  - Evidence checklist incomplete (`:evidence-incomplete`)
  - Field-equipment operator certification expired (`:operator-certification-expired`) — only for field-equipment-operation service types
  - Field-equipment safety inspection overdue (`:equipment-inspection-overdue`) — only for field-equipment-operation service types
  - Wind speed exceeded the safe fieldwork ceiling (`:wind-speed-exceeded`) — only where a wind-speed ceiling genuinely applies to that service type
  - Riparian buffer narrower than the service type's minimum (`:riparian-buffer-violated`) — only where a riparian-buffer minimum genuinely applies
  - Haul-road load exceeded the service type's maximum (`:load-limit-exceeded`) — only for in-forest log transport
  - Proposal covertly requests direct forestry-equipment control or a final fire-suppression-tactic decision (`:forestry-equipment-or-fire-tactic-decision-blocked`) — a HARD, PERMANENT block, defense-in-depth against every op
  - Unresolved forest-health concern (`:forest-health-flag-unresolved`)
  - Service order already logged (`:already-logged`, double-commit guard)
- **Escalate** (human sign-off always required):
  - `:log-service-record` — the one real actuation event this actor performs, always requires human sign-off even when the Governor is otherwise clean
  - `:flag-forest-health-concern` — a forest-health concern (pest, disease, fire risk) is never auto-resolved by advisor confidence alone
  - `:order-supplies` above `governor/supply-order-cost-threshold-usd` (5000 USD)
  - Low advisor confidence (below `governor/confidence-floor`, 0.6)
- **Commit** (advisor proposal approved; Governor clean; not a mandatory-escalation op):
  - Routine, low-stakes proposals only — in this actor's current allowlist that is effectively `:schedule-field-operation` when clean, or `:order-supplies` at or below the cost threshold

### Operations (Proposals)

Closed allowlist — the advisor may **only** ever propose these four operation types, all `:effect :propose`:

- **`:log-service-record`** — Log timber-cruising/seed-collection/fire-protection-support/log-transport service-hours data, plus safety/compliance parameters, into service records (always requires human sign-off)
- **`:schedule-field-operation`** — Propose timber-cruising/fire-protection-support/seed-collection/log-transport service scheduling for a client forest operator (routine, low risk)
- **`:flag-forest-health-concern`** — Surface a forest-health concern (e.g. pest infestation, disease, fire risk); always escalates
- **`:order-supplies`** — Propose equipment/fuel procurement (escalates above the cost threshold)

Any proposal for an operation outside this allowlist — most importantly anything that would amount to direct forestry-equipment control — is refused unconditionally by the Governor (`:op-not-allowed`), regardless of advisor confidence. Any proposal that covertly requests direct forestry-equipment control or a final fire-suppression-tactic decision, even nested inside an otherwise-allowed op, is likewise refused unconditionally (`:forestry-equipment-or-fire-tactic-decision-blocked`).

## Testing

```bash
# Run full test suite
clojure -M:test

# Check code quality
clojure -M:lint

# Run demo simulation
clojure -M:run
```

## Standalone Use

This repo is **forkable outside the workspace**. If cloning standalone (not in the kotoba-lang monorepo), override `:local/root` paths in `deps.edn`:

```clojure
{:deps {io.github.kotoba-lang/langchain {:git/url "https://github.com/kotoba-lang/langchain" :git/tag "v0.1.0"}
        io.github.kotoba-lang/langgraph {:git/url "https://github.com/kotoba-lang/langgraph" :git/tag "v0.1.0"}}}
```

## License

AGPL-3.0-or-later. Forking/contribution welcome; see `CONTRIBUTING.md`.

## Security

Report security issues to the issue tracker or private disclosure; see `SECURITY.md`.

---

Part of **cloud-itonami**: autonomous actor fleet for regulated industries. See [github.com/cloud-itonami](https://github.com/cloud-itonami).
