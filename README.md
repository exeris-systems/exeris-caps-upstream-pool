# exeris-caps-upstream-pool

Upstream clusters and load balancing as an Exeris capability. `UpstreamPoolService` is an in-memory registry of named `UpstreamCluster` instances; a cluster holds `UpstreamHost` instances, selects one per request with a `LoadBalancer`, and removes hosts that keep failing.

Status: proof of concept, built against `exeris-kernel` 0.13.0-SNAPSHOT on JDK 25 LTS. The API is not stable and no artefact has been published.

## Where it sits

- **Layer:** Layer 2 — Gateway building block, in the Tier 2 capability map of [`high-level-architecture.md`](https://github.com/exeris-systems/exeris-docs/blob/main/high-level-architecture.md) §3.2.
- **Depends on:** `exeris-sdk-annotations` (`provided`) and `exeris-kernel-spi`, used only for the entitlement requirement.
- **Provides** (`@Provides`, contract version 0.1.0): `UpstreamPoolService`.
- **Requires:** no other capability.
- **Wall (ADR-055):** `eu.exeris.caps.pool.api` is the cross-cap surface; `eu.exeris.caps.pool.internal` is private, and a sibling cap reaching into it fails the build. No Spring, servlet, Netty or reactive type may appear (ADR-006).

## What the source contains

- `UpstreamHost` tracks health status, in-flight connections and request and failure counts with atomics (`AtomicUpstreamHost`).
- `LoadBalancer` implementations cover round-robin, least-connections, smooth weighted round-robin, random and key-affine `IP_HASH` (the caller supplies the key; `UpstreamPools.addressKey(byte[])` folds raw address bytes into one); `LoadBalancingAlgorithm` names the choice, and `UpstreamPools` is the factory. Selection scans `UpstreamCluster.allHosts()` by index, takes no lock and, after warm-up, allocates nothing; the tests measure allocation with the kernel TCK's `JfrAllocationMonitor` (zero contract) and inspect the classes for locks.
- `PassiveHealthCheckConfig` ejects a host from the healthy set for a configured duration after a configured number of consecutive 5xx responses or transport errors. Its `maxEjectionPercent` is a cluster-level budget shared with the prober: at most `max(1, floor(hosts * percent / 100))` hosts are ejected at once and one non-ejected, non-draining host always remains, so a one-host cluster never ejects it.
- `ActiveHealthCheckConfig` configures periodic background probing; `DefaultUpstreamCluster` runs the prober on a scheduled executor and stops it on `shutdown()` (`UpstreamPoolService.close()` shuts down every cluster). The prober decides on its own, with or without passive checking: `unhealthyThreshold` consecutive failed probes eject a host, `healthyThreshold` consecutive successful probes (2xx) reinstate it.

## Build & test

Requires JDK 25 and Maven 3.9+.

```bash
mvn compile -Dexeris.codegen.skip=true   # pass 1 — the processor writes capability metadata
mvn verify                               # pass 2 — generate cap-manifest.json, validate the graph, scan the Wall
```

Both passes are needed after a `clean`: `exeris:generate` runs before `compile`, and `compile` is when the processor writes the metadata it reads. `src/main/generated/java/cap-manifest.json` is committed, so a clean `git status` after `mvn verify` shows the manifest matches the declaration.

The upstreams are not on Maven Central. `exeris-sdk` and `exeris-tooling` are built from source, and `exeris-kernel` 0.13.0-SNAPSHOT is resolved from GitHub Packages, which needs a token with `read:packages`.

## Documentation

- [`AGENTS.md`](AGENTS.md) — the repository's operating contract and invariants.
- [Capability Author Guide](https://github.com/exeris-systems/exeris-docs/blob/main/cap-author-guide.md) — how a cap declares `@CapabilityModule`, `@Provides`, `@Requires` and `@CapabilityLifecycle`.
- [Capability licence registry](https://github.com/exeris-systems/exeris-docs/blob/main/cap-license-registry.md) — the licence value of every cap.

Where they disagree, the ADRs below and the HLA outrank this README.

## Governing ADRs

- [ADR-006](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-006-spring-free-kernel-boundary.md) — Spring-free kernel boundary.
- [ADR-023](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-023-capability-licensing-taxonomy.md) — capability licensing taxonomy.
- [ADR-024](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-024-capability-composition-model.md) — capability composition model.
- [ADR-053](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-053-sku-composition-manifest-format.md) — composition manifest format.
- [ADR-055](https://github.com/exeris-systems/exeris-tooling/blob/main/docs/adr/ADR-055-cap-tier-wall-guard.md) — cap-tier Wall guard.
- [ADR-088](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-088-cryptographic-license-manifest-and-offline-verification.md) — signed licence manifest.
- [ADR-089](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-089-capability-entitlement-enforcement-and-runtime-contract.md) — capability entitlement enforcement.

Registry: [`adr-index.md`](https://github.com/exeris-systems/exeris-docs/blob/main/adr-index.md).

## License

Exeris Commercial License 1.1 — see [`LICENSE`](LICENSE). `exeris-caps-upstream-pool` is a `commercial` capability under [ADR-023](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-023-capability-licensing-taxonomy.md): the source is available to read, build, test and evaluate, and production use requires a commercial licence.

Its entitlement id is `exeris-caps-upstream-pool`, the artifactId ([ADR-088](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-088-cryptographic-license-manifest-and-offline-verification.md), [ADR-089](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-089-capability-entitlement-enforcement-and-runtime-contract.md)).

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Report a suspected vulnerability as described in [`SECURITY.md`](SECURITY.md), never in a public issue.
