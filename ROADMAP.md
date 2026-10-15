---
title: "exeris-caps-upstream-pool — Roadmap"
type: roadmap
visibility: public
owning-repo: exeris-caps-upstream-pool
status: active
last-verified: 2026-10-15
---

# exeris-caps-upstream-pool — Roadmap

Status: **poc**. Version line: **0.1.x**. Licence: Exeris Commercial License (ADR-023), entitlement id
`exeris-caps-upstream-pool`.

Milestones are versions, not dates. Dates appear only on what has shipped; nothing has shipped yet.

> **`1.0.0` is not on this roadmap.** It is a gate, described in the last section. Status moves
> `poc` → `snapshot` → `released`.

---

## Where this sits

Layer 2 gateway building block. Provides `UpstreamPoolService`: named `UpstreamCluster`s of
`UpstreamHost`s, built through `UpstreamPools`, with round-robin, weighted (smooth) round-robin
and least-connections selection (`IP_HASH` and `RANDOM` are declared in the API and balance as round-robin),
passive health (consecutive-error ejection with a quarantine
window) and an opt-in active prober. One compile dependency, `exeris-kernel-spi`, used only for the `UpstreamPoolEntitlement` requirement; no
`@Requires`. 55 tests across nine classes.

Gaps between the inventory and the code, found in the code:

- HLA §3.2 names the service `UpstreamPool` and lists `@Requires gateway-core`; the code provides
  `UpstreamPoolService` and requires nothing. HLA also places `backend-health` in a separate cap; here
  passive and active health live in this one.
- The active prober runs on a thread executor owned by the cluster and uses the JDK `HttpClient`, not the
  kernel client engine; there is no `@CapabilityLifecycle`, so shutdown depends on callers invoking
  `UpstreamCluster.shutdown()`.
- `LoadBalancingAlgorithm.IP_HASH` exists in the API; the SKU maps only three of the four algorithms.

**Upstream constraints this repo does not own:**

| Constraint | Effect here | Owner |
|---|---|---|
| Kernel licence gate (ADR-088, ADR-089) in kernel `0.13.0-SNAPSHOT` | Entitlement resolvable only from that line | `exeris-kernel` |
| Kernel HTTP client engine (`HttpClientEngine`) | Whether the prober should use it instead of the JDK client | `exeris-kernel` |
| HLA §3.2 row differs from the code | Registry names `UpstreamPool` and a `gateway-core` requirement | `exeris-docs` |
| Kernel `HttpExchange` does not expose the peer address yet | `IP_HASH` end to end needs the connection address | `exeris-kernel` |
| `@CapabilityLifecycle` conventions | A resource-owning cap needs hooks the composition runtime calls | `exeris-sdk`, `exeris-tooling` |

---

## 0.1.0 — the code PR

- [ ] Defects found in the pre-import review are fixed in the import, each with a test
- [ ] Code imported; repository files and ADR link stubs added
- [ ] `@Provides(UpstreamPoolService)` at contract `0.1.0`
- [ ] Kernel `0.13.0-SNAPSHOT`; SDK `0.12.0` and tooling `0.10.0` pins aligned
- [ ] Artefacts stay `0.1.0-SNAPSHOT` until kernel `0.13.0` is released; a non-snapshot `0.1.0` is cut only after that
- [ ] Licence enforcement: `UpstreamPoolEntitlement` (id `exeris-caps-upstream-pool`), `META-INF/services`
      registration, TCK test and boot test against the kernel's Phase 0 gate
- [ ] `build.yml` green on JDK 25 and 26; `cap-manifest.json` committed through the two-pass build

**Exit gate:** `mvn verify` green on both JDK rows; entitlement test fails without the id.

## 0.2.0 — lifecycle and the filter-chain change

The pool is not a policy and has no chain binding of its own (the chain is ADR-099's `RequestFilterChain`); this milestone is about what the
`RequestFilterChain` change leaves it with.

- [ ] `@CapabilityLifecycle` so prober threads stop on `drain` / `terminate` rather than on a caller remembering `shutdown()`
- [ ] Record outcome reporting (`recordResult`) as the single path by which proxy handlers and the
      circuit-breaker binding feed host health, so removing the SKU-side policy adapters does not drop it
- [ ] Upstream TLS configuration expressed in the cap (certificate verification, SNI, client certificates)
- [ ] `IP_HASH` balancing available end to end, including the SKU mapping; the client address comes from the
      connection unless a trusted-proxy list says otherwise
- [ ] The same upstream outcome feeds both `recordResult` and the circuit breaker; whether both use one
      `FailureClassifier` or differ on purpose is written down (see circuit-breaker 0.2.0)
- [ ] Align `HLA §3.2` (service name, requirements, `backend-health` overlap) through an `exeris-docs` PR

## 0.3.0 — Independent security review

- [ ] Covered by the API Gateway SKU review (one review; scope: the SKU and every composed cap); this
      repository's public API and documented behaviour are listed in that scope
- [ ] Threat model written and committed alongside the code
- [ ] Findings handled through `SECURITY.md` private reporting
- [ ] Fixes released, each with a regression test

## 0.4.0 — re-measurement and snapshot

- [ ] Host selection measured per algorithm in the `exeris-benchmarks` harness on the reference profile, published there; no figure stated here
- [ ] Snapshot artefact on GitHub Packages; status `poc` → `snapshot`

---

## 1.0.0 — a gate, not a milestone

Entry condition: the API Gateway SKU has used the pool end to end with ejection and recovery exercised, the
review is closed, and the overlap with `backend-health` is resolved. Then `UpstreamPoolService` freezes,
a semver gate is added, and the status becomes `released`.

## Deliberately out of scope

- DNS or service-discovery sources, connection pooling at the socket level: not in this repository; the kernel owns transport.
- A separate `backend-health` cap: tracked in HLA §3.2, decided with the overlap above.
