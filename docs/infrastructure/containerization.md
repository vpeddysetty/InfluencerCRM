# Containerization — the images, and how they map onto Fargate

**Date:** 2026-08-09
**Status:** images built and verified locally. **No AWS resources are created by anything here.**
**Scope:** the ten Spring services and the Python agent. The UIs are static sites and are covered by
[static-site-architecture.md](static-site-architecture.md), not this document.
**Target:** all eleven containers in **one Fargate task definition**. Redis is out of scope this
phase — see below.

---

## The one idea

**The image is a constant; the environment is the variable.**

Every difference between a laptop and Fargate is an environment variable. Nothing is baked in, and
there is no `-Dspring.profiles.active=prod` build. That is what makes `docker compose up` a genuine
rehearsal rather than a separate system that happens to compile the same source: the artifact that
passes locally is bit-for-bit the artifact that runs in the cluster.

Two consequences worth stating plainly:

- **No secret is ever in an image layer.** Anything secret arrives at `docker run` / task start.
- **A missing variable fails at startup, not at first use.** Where a default would have been
  dangerous (a keystore password, a service token) the properties files say so in a comment.

---

## What is built

| Image | Dockerfile | Build arg | Port |
|---|---|---|---|
| `influencrm/dao` | `docker/Dockerfile.service` | `MODULE=InfluencerDAO` | 8443 (HTTPS) |
| `influencrm/web-experience` | same | `MODULE=InfluencerWebExperience` | 8081, 9081 (mgmt) |
| `influencrm/dps` | same | `MODULE=InfluencerPresentationService` | 8090 |
| `influencrm/workflow` | same | `MODULE=InfluencerWorkflowService` | 8444 |
| `influencrm/identity` | same | `MODULE=InfluencerIdentityService` | 8445 |
| `influencrm/creator` | same | `MODULE=InfluencerCreatorService` | 8446 |
| `influencrm/campaign` | same | `MODULE=InfluencerCampaignService` | 8447 |
| `influencrm/attribution` | same | `MODULE=InfluencerAttributionService` | 8448 |
| `influencrm/finance` | same | `MODULE=InfluencerFinanceService` | 8449 |
| `influencrm/content` | same | `MODULE=InfluencerContentService` | 8450 |
| `influencrm/agent` | `docker/Dockerfile.agent` | — | 8000 |

**One Dockerfile for ten services, not ten Dockerfiles.** The services differ only in which module
is built and which port it listens on. Ten near-identical files would drift — someone bumps the base
image in nine of them and the tenth silently ships an old JRE.

```bash
docker build -f docker/Dockerfile.service --build-arg MODULE=InfluencerDAO -t influencrm/dao:v1 .
```

**Build from the repo root.** The context has to be the root because every service depends on
`InfluencerPlatformCommons`, a sibling module that must be installed into the local Maven repository
before the service's own build can resolve it. `.dockerignore` is what keeps that context small.

### Choices inside the images

| Choice | Why |
|---|---|
| Multi-stage, `maven:3.9-eclipse-temurin-17` → `eclipse-temurin:17-jre` | The compiler and its toolchain are build-time concerns and a needlessly large attack surface to ship |
| Non-root `uid:gid 1001`, **same in the Python image** | Every service writes to one shared EFS access point; they must agree on ownership or one service's files become unwritable by the next |
| `-XX:MaxRAMPercentage=75` (no fixed `-Xmx`) | The JVM sizes its heap from the actual cgroup limit, so one image is correct at 512MB and at 4GB. A hardcoded heap is wasteful on a large task or OOM-killed on a small one. **With everything in one task, set `memory` per container** — 75% of an unset limit is 75% of the whole task, eleven times over |
| `exec` in the entrypoint | The JVM becomes PID 1 and receives `SIGTERM` directly. Without it the shell holds PID 1, swallows the signal, and Fargate waits out the full stop timeout before `SIGKILL` — every deploy becomes a rolling 30-second stall with no clean drain |
| Tests skipped at build | They ran in CI against the tagged commit, and several need a live Postgres that does not exist in a build container |
| Python 3.12, not 3.14 | `psycopg[binary]` and the rest of the set ship prebuilt wheels for 3.12; pinning stops the image silently compiling from source when a wheel lags a release |

---

## Logging → EFS

**One directory, every service, one access point.** `LOG_DIR=/mnt/logs` in every image; every task
mounts the *same* EFS access point there. That is what makes the centralized design work in
Fargate: all services append JSON lines into one shared directory, so `rid` still ties a single
browser action across the whole chain the way it does locally.

Four things had to change for this to be true:

1. **`logback-spring.xml` now lives once, in Commons** (`logback-influencrm.xml`), included by each
   service in three lines. Three services previously carried near-identical copies.
2. **Eight of eleven services had no file appender at all** — they logged only to the console. The
   centralized story had eight holes in it. They all have one now.
3. **`spring.application.name` is set in every service.** It names the log file
   (`influencrm-<name>.log`) and tags every line. The shared config has **no default** for it, so a
   service that forgets fails at startup rather than writing to `influencrm-null.log` or colliding
   with another forgetful service in the same shared directory.
4. **The seven extracted services did not depend on Commons at all.** Only the three original
   services (DAO, BFF, DPS) did. The include therefore resolved to nothing for exactly those seven:
   logback logged `Could not find resource corresponding to [logback-influencrm.xml]`, silently fell
   back to console-only, and they wrote no file — while still reporting healthy. Commons is now a
   dependency of all ten. It holds only `observability` and `workload`, no domain code, so it crosses
   no context boundary.

**How this failure presented is the lesson.** Nothing errored. The services started, passed their
health checks, served traffic, and produced a shared log directory that looked correct because three
services were writing to it. Only counting the files against the service list showed seven missing.
A `docker compose up` that reports all-healthy is not evidence that logging works — check
`ls /mnt/logs` and count.

### The EFS access point must use uid/gid 1001

Non-negotiable. The images run as `influencrm` (1001) and a mount shadows the directory's
in-image ownership, so an access point owned by anything else makes every append fail — at runtime,
per line, after the service has started successfully.

```
POSIX user:      uid 1001, gid 1001
Root directory:  /logs   (creation perms 0755, owner 1001:1001)
```

`/mnt/logs` is also created *inside* the image and owned by the runtime user, so a plain
`docker run` with nothing mounted still starts — it writes to the container's own copy instead of
crashing. That is deliberate: a developer running one image should not need EFS.

### Size it for N services

The rolling policy caps each service at **3GB** (100MB × files, 14 days). On a shared volume the
ceiling is therefore `3GB × service count` ≈ **33GB** for eleven. An uncapped log directory fills
the volume, and on EFS that volume is shared by everything — one chatty service would take the
whole platform's logging down with it.

**CloudWatch is still worth configuring**, via the `awslogs` driver, and the images keep their
console appender for exactly that reason. EFS holds the structured JSON that the `rid` correlation
depends on; CloudWatch is what you read when a task dies before it can write a file.

---

## Secrets → Secrets Manager

**Fargate injects secrets natively. No service calls an AWS SDK to fetch configuration.**

The task definition's `secrets` block maps a Secrets Manager value to an environment variable, and
the properties files already read `${VAR:default}`. Nothing in the application changes.

```json
"secrets": [
  { "name": "DAO_DB_PASSWORD",  "valueFrom": "arn:aws:secretsmanager:...:secret:influencrm/dao/db-password" },
  { "name": "DAO_SERVICE_TOKEN","valueFrom": "arn:aws:secretsmanager:...:secret:influencrm/dao/service-token" }
]
```

Why this and not `spring-cloud-aws-secrets-manager`: it needs no new dependency in ten services, no
IAM surface inside the app, and no new startup failure mode. The cost is that a rotated secret
needs a task restart — acceptable, and the tradeoff to revisit only if rotation frequency makes it
hurt.

`environment` and `secrets` differ in one way that matters: **`environment` values are visible in
the console and in `describe-task-definition`.** Anything sensitive goes in `secrets`, always.

### The values that must be set, per service

These have committed development defaults that are **not safe anywhere else**. Each is documented
at its definition site; this is the checklist.

| Variable | Service | Why it cannot keep its default |
|---|---|---|
| `DAO_DB_PASSWORD`, `*_DB_PASSWORD` | all | `password` / `change-me-<ctx>` |
| `DAO_SERVICE_TOKEN` | DAO + BFF | Committed shared token; grants full DAO access |
| `WORKFLOW_SERVICE_TOKEN` | BFF + workflow | Same |
| `DPS_SERVICE_TOKEN` | DPS | Same |
| `DAO_KEYSTORE_PASSWORD` | DAO | TLS private key |
| `WEBE_JWT_SIGNING_KEY` | BFF | Unset ⇒ ephemeral key: tokens die on restart and a second task cannot verify the first's |
| `WEBE_WORKLOAD_SIGNING_KEY` / `_PRIVATE_KEY` | BFF | Workload identity; see [zero-trust chain](../../EXECUTION-ROADMAP.md) |
| `DPS_WORKLOAD_SIGNING_KEY` / `_PRIVATE_KEY` | DPS | Same |
| `WEBE_MARKETPLACE_CREDENTIAL_KEY` | BFF | Unset ⇒ connect refuses before the handshake (deliberate) |
| `OPENAI_API_KEY` | agent | Currently in a committed `.env` — **see the warning below** |

> **`.env` contains a live OpenAI key.** It is `.dockerignore`d and `.gitignore`d so it reaches
> neither an image nor a new commit, but it is a real credential sitting in the working tree, and
> anything already in git history stays there. Treat it as compromised, rotate it, and put the new
> one in Secrets Manager — the same conclusion [keystore-rotation.md](../keystore-rotation.md)
> reached about the DAO's private key.

### Beyond the app: AWS-side settings

- **`readonlyRootFilesystem: true`** is compatible with these images *for the Java services* — the
  entrypoint writes only to `/dev/shm` (tmpfs). The BFF additionally needs its `/mnt/assets` mount
  writable, which a volume provides regardless of the root filesystem.
- **`stopTimeout`** — leave at or above 30s so Spring can drain. The `exec` choice above is what
  makes the timeout meaningful.
- **`essential`** — mark the BFF, DPS and DAO essential. Leave the extracted services non-essential
  so one of them dying does not take down the whole task, which in a single-task deployment is the
  entire platform.
- **`dependsOn`** with `condition: HEALTHY` reproduces compose's ordering: the DAO before the BFF,
  the BFF before the DPS. Without it every container starts at once and the BFF's first DAO calls
  fail until the DAO finishes booting.

### Memory, specifically

Eleven JVMs in one task each honouring `MaxRAMPercentage=75` will overcommit badly if per-container
memory is unset — each would read 75% of the *task's* total. Set `memory` (a hard limit) or
`memoryReservation` on every container. A reasonable starting split, to be tuned against real usage:

| Container | Suggested |
|---|---|
| DAO (45 JPA repositories, largest schema) | 1024 MB |
| BFF | 1024 MB |
| DPS (holds sessions in heap this phase) | 768 MB |
| each of 7 extracted services | 512 MB |
| agent (Python) | 512 MB |

That is ~7.5 GB before overhead, so the task needs 8 GB and CPU to match. **A single task is not a
small task here** — this is the main cost consequence of the one-task decision.

---

## TLS: what KMS can and cannot do

**KMS never releases a key.** It signs, encrypts and decrypts *inside* the service; you cannot
export a private key from it. So "get the keystore from KMS" is not directly possible, and there is
no code path that would make it possible.

What is possible, and what this repo does:

**The PKCS12 lives in Secrets Manager as a base64 binary secret, encrypted at rest with a KMS
customer-managed key.** That is the KMS involvement — it protects the secret; it does not serve the
file. `docker/entrypoint-service.sh` decodes it into `/dev/shm` (tmpfs) and execs the JVM.

```json
"secrets": [
  { "name": "DAO_KEYSTORE_B64",     "valueFrom": "arn:...:secret:influencrm/dao/keystore-b64" },
  { "name": "DAO_KEYSTORE_PASSWORD","valueFrom": "arn:...:secret:influencrm/dao/keystore-password" }
]
```

```bash
# Storing it
base64 -w0 keystore.p12 > keystore.b64
aws secretsmanager create-secret --name influencrm/dao/keystore-b64 \
  --kms-key-id alias/influencrm --secret-string file://keystore.b64
```

`/dev/shm` and not `/tmp`: the decoded private key never touches a disk, never survives the task,
is not readable by anything that later gets a shell on a container layer, and does not block
`readonlyRootFilesystem`. The script writes it under `umask 077` rather than `chmod`-ing afterwards,
because chmod leaves a window where the file exists world-readable.

**The script is a no-op when the variable is unset**, so the same image and the same entrypoint
serve local runs and any service that terminates TLS at the ALB. Behaviour is opt-in by the
presence of a secret — not by a second image or a build flag.

### Why the DAO keeps its own certificate

TLS *could* terminate at an internal ALB, leaving services on plain HTTP. That was considered and
rejected for the BFF→DAO hop specifically: the BFF verifies the DAO's certificate against
`dao-trust-store`, and that check is the inner hop of the zero-trust chain. Terminating at the ALB
would leave the trust decision resting on security groups plus a shared token — and the failure mode
is silent, because the BFF would go on working while verifying nothing.

The truststore holds only a **public** certificate, is committed deliberately, and is loaded from
the classpath. `.dockerignore` blanket-excludes `*.p12` and re-admits `dao-truststore.p12` by
negation — without that exception the BFF image would ship with no truststore and fail closed on
its first DAO call.

**A CA-issued certificate (ACM Private CA, or an internal CA) is better than this self-signed one**
and removes the custom truststore entirely, as [keystore-rotation.md](../keystore-rotation.md)
already notes. The rotation path exists in the meantime: `WEBE_DAO_TRUST_STORE_B64` updates the
BFF's truststore from a secret, so rotating the DAO's certificate is a secret update and a restart
rather than an image rebuild.

---

## Service discovery — one task definition

**All eleven containers go into a single Fargate task definition.** Containers in one task share a
network namespace, so they reach each other on **`localhost:<port>`** — no Service Connect, no Cloud
Map, no DNS, no service mesh.

That has a pleasant consequence: **`localhost` is already every URL variable's default.** The task
definition therefore sets *fewer* of these than compose does, not more.

| Variable | Compose (separate namespaces) | Single Fargate task |
|---|---|---|
| `WEBE_DAO_BASE_URL` | `https://dao:8443` | *default* — `https://localhost:8443` |
| `WEBE_AGENT_BASE_URL` | `http://agent:8000` | *default* |
| `DPS_BFF_URL` | `http://web-experience:8081` | *default* |
| `CREATOR_SERVICE_URL` | `http://creator:8446` | *default* |

Compose cannot reproduce a shared namespace — each service is its own — so it resolves by service
*name* on a bridge network. This is the one genuine local/deployed difference, and it is contained
entirely in these variables. The images are identical either way.

**The DAO's certificate carries `localhost` as a SAN for exactly this reason**, so BFF→DAO TLS
verification keeps working in the shared namespace with no reissue. If services are later split into
separate tasks, the certificate must gain the new DNS name **before** the URLs change. The tempting
fix at that moment is `WEBE_DAO_TLS_VERIFICATION=false`, which silently disables the verification
this section exists to preserve. Reissue the certificate instead.

### What a single task costs

Worth writing down so it stays a decision rather than a surprise:

- **No per-service scaling.** The unit of scale is all eleven containers.
- **One container's OOM kill can take the task down**, and `MaxRAMPercentage=75` is per container —
  eleven JVMs each claiming 75% of the *task's* memory will overcommit. **Set `memory` per container
  in the task definition**, not just at task level; that is what each JVM's 75% is then measured
  against.
- **Sessions die on redeploy** (see Redis below).
- Task-level CPU/memory must cover the sum. Eleven JVMs plus a Python process is not a 0.5 vCPU task.

The reason the URLs stay variables rather than being hardcoded to `localhost`: splitting services
into their own tasks later becomes a change of values, not of code.

---

## Redis: dropped in this phase

Redis is **not** part of this deployment. Its only consumer is the DPS session store, and the problem
it solves — sharing sessions across instances — does not exist while the whole platform is one task
running one DPS. `SessionStoreConfig` falls back to in-memory Caffeine whenever
`dps.session-store` is not `redis`, so this needs no override; it needs the variable left unset.

**The cost:** sessions live in the DPS heap, so a task restart or redeploy logs every signed-in user
out. Acceptable for a first deployment, and it is the trade to revisit first.

**Bring Redis (or ElastiCache) back before running more than one DPS.** Without it, users get logged
out at random as requests land on different instances — a failure that looks intermittent and is
really structural. `SessionStoreConfig` logs which store is active at startup; that line is the
check.

---

## Health checks — nine of eleven services were unprobeable

This was the largest single finding of the exercise, and none of it is visible when the services run
on a laptop. **Every service authenticates its own port**, and an orchestrator cannot present a
service token. So the health endpoint has to be one the service already exempts.

| Service | Before | Now |
|---|---|---|
| DAO | **No actuator dependency at all.** `/health` was exempted by the security config and both filters, but nothing served it → 404 | actuator added, mapped to `/health` |
| 7 extracted services | `/actuator/health` → **401**, and no healthcheck was declared, so compose reported a bare "Up" for a service nothing had checked | mapped to `/health`, healthcheck added |
| DPS | Started fine, reported **DOWN** | Redis indicator disabled (below) |
| BFF | `/actuator/health` on the separate management port 9081, unauthenticated | unchanged, already correct |

**Why mapping and not a security change.** Each service's `ServiceTokenFilter#shouldNotFilter`
exempts exactly `/health` — one string. Mapping actuator onto that path means the exemption that
already exists is the one being used; the alternative was widening seven security configs to admit a
second unauthenticated path, which is a larger change with a worse failure mode.

Exposure stays narrowed to `health` (and `info`). On the DAO especially, `env`, `configprops` and
`heapdump` would leak the database password and the service token.

**In Fargate this is the difference between a service and a crash loop**: an ALB target group that
gets 401 or 404 from its health check never registers the task, and ECS cycles it forever. Nine
services would have done exactly that.

### The DPS reported DOWN for a Redis it does not use

Spring's Redis health indicator auto-activates whenever `spring-data-redis` is on the classpath —
which it always is, because `RedisSessionStore` is a compile-time option. With Redis dropped this
phase, the DPS started, served traffic, logged that it was using in-memory sessions, and reported
`status: DOWN`.

`management.health.redis.enabled` is now tied to the same switch that chooses the store, so the check
is active exactly when Redis is in use. Setting `dps.session-store=redis` re-enables it, and
`SessionStoreConfig` already refuses to start if Redis is then unreachable — nothing ends up silently
unmonitored.

### `start_period` is load-bearing

`start_period` (compose) / `startPeriod` (Fargate) matters more than it looks. These services run
Hibernate against a large schema and a cold start is comfortably slower than the check interval;
without it the first checks count as failures and the orchestrator restarts a container that was only
still booting — a crash loop that looks like a broken image.

---

## Running it locally

```bash
docker compose build        # ~10 min cold; the Commons layer is shared across services
docker compose up -d
docker compose ps           # all services healthy
docker compose logs -f web-experience
```

**The database init scripts run only on an empty data directory.** After a schema change:

```bash
docker compose down -v && docker compose up -d
```

Without `-v` the volume persists and the new migration never runs — which presents as a broken
migration rather than one that was never applied.

> **Eleven migrations were missing from `schema/zzz_apply_migrations.sql`** — everything from
> `2026_08_05_phase_a_landing_builder` onward except one. Since that file is now how *every*
> containerized database is created, a fresh stack came up with no landing builder, assets, creator
> onboarding, vetting, domains, collaborators, billing, expiry warnings, or order-idempotency index,
> and services failed on first query against a missing table. They are added in filename date order,
> which matters: `phase_a` creates the landing tables that `phase_b` and `m5_6` then alter.
>
> Verified applied in a from-scratch containerized database: `content.landing_template_versions`,
> `content.assets`, `content.landing_page_collaborators`, `identity.subscriptions`,
> `content.brand_domains`, `creator_platform_tokens`, and both `uq_isa_*` unique indexes on
> `attribution.influencer_sale_attributions`.

### Verifying a run

Tables are **schema-qualified** (`content.assets`, `attribution.influencer_sale_attributions`), so an
unqualified name in a check query returns zero rows and looks exactly like a migration that never
ran. Query `information_schema.tables` with `table_schema` included.

```bash
docker compose ps                                   # every service should read (healthy)
docker exec influencrm-dao ls -1 /mnt/logs/          # expect TEN influencrm-*.log files
curl -sk https://localhost:8443/health               # {"status":"UP", ...}
```

The log-file count is the check that catches the failure mode above; "all healthy" does not.

---

## What is deliberately not done here

- **No ECS task definitions, no Terraform/CDK, no AWS resources.** The mappings above are what a
  task definition needs; writing unverifiable JSON was out of scope for this pass.
- **The UIs are not containerized.** They are static sites behind CloudFront — a container serving
  static files would be a worse deployment of the same artifact.
- **No image scanning or registry push.** ECR lifecycle policy, tag immutability and scan-on-push
  are deployment-pipeline decisions, not image decisions.
- **The app tier is still unconstrained by the SNI decision** in
  [hosting-topology-decision.md](hosting-topology-decision.md), which explicitly left "where the app
  tier runs" open. Fargate is consistent with it; nothing here forecloses Option C.
