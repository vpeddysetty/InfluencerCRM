# InfluenCRM — deployment architecture

**Environment:** test (the only one that exists). AWS account `099933382956`, region `us-east-1`.
**Status:** deployed and verified end-to-end on 2026-08-10.

Production is on the roadmap for the subscription-tier launch. See
[prod/README.md](prod/README.md) — it is not a copy of this with a different variable.

---

## 1. The shape of it

Everything the platform runs on is **one EC2 Spot instance**. Postgres is the one thing that isn't.

```
                     browser
                        │
        ┌───────────────┴────────────────┐
        │                                │
   CloudFront ×7                    Elastic IP
   (micro-frontends)               100.58.135.154
        │                                │
    S3: UI bundles              ┌────────┴────────┐
                                │  Caddy :80/:443 │   ← TLS + reverse proxy
                                └────────┬────────┘
                                         │  docker bridge network
              ┌──────────────┬───────────┼───────────┬──────────────┐
              │              │           │           │              │
          web-experience    dps        agent       dao         (7 context
            (BFF :8081)   (:8090)    (:8000)    (:8443 TLS)     services,
              │              │                      │          profile off)
              └──────────────┴──────────┬───────────┘
                                        │
                        ┌───────────────┼───────────────┐
                        │               │               │
                   RDS Postgres        EFS         CloudWatch Logs
                   (db.t4g.small)   3 access pts    /influencrm/prod
                                   logs/assets/caddy
```

One `t3a.large` in `us-east-1a`, running five containers under `docker compose`, supervised by systemd.

## 2. Why there is no orchestrator

The platform ran on ECS until 2026-08-10 and **never successfully started a task**. The capacity
provider's managed scaling wanted two instances; the boot script assumed exactly one owner of a single
EBS volume holding Postgres. Instance A held the volume and failed device discovery. Instance B waited
for a volume that would never be released. Zero container instances ever registered.

The fix was not to tune the scaler. This deployment **can only ever have one instance**:

- DPS holds sessions in the JVM heap (no Redis), so a second instance logs users out at random.
- One Elastic IP is the DNS target, and it attaches to one instance.

So the component that wanted to scale was deleted, and `max_size = 1` makes a second instance
structurally impossible rather than merely unlikely. Moving Postgres to RDS removed the contended volume
entirely and made the instance **stateless** — which is the whole point of running on Spot: a reclamation
costs availability, not data.

Full account: [COMPOSE-MIGRATION.md](COMPOSE-MIGRATION.md).

## 3. Boot sequence

`compose-boot.sh.tftpl` runs from user data (gzipped — see §7) on every launch, including Spot
replacement. It is idempotent and assumes no clean slate.

1. **Packages** — Docker, the compose plugin, EFS utils.
2. **EFS** — mounts three access points by ID with `tls,iam`: `/mnt/logs`, `/mnt/assets`, `/mnt/caddy`.
   A failed mount warns but does not abort; a running platform writing to local disk beats no platform.
3. **Elastic IP** — the instance claims it by tag, so DNS survives replacement.
4. **Secrets** — fetches 19 secrets into `/run/influencrm/platform.env` (root-only, mode 0600, on tmpfs
   so they die with the boot).
5. **Compose** — pulls `docker-compose.yml` from S3, logs into ECR, writes and starts
   `influencrm.service`.
6. **Spot watcher** — polls the interruption endpoint; on the two-minute notice it stops the stack
   gracefully instead of letting Spring be killed mid-request.

## 4. Container startup order

Compose enforces this with `depends_on` conditions — the same ordering ECS expressed with `dependsOn`:

```
migrate (runs to completion, exit 0)
   └─> dao (healthy)
         └─> web-experience (healthy)
               └─> dps (healthy)
                     └─> caddy
   └─> agent
```

`migrate` uses `service_completed_successfully`, so **a failed migration means nothing starts**. That is
deliberate — a service running against a half-built schema fails later, on a query, looking like an
application bug.

**The migration is idempotent as of 2026-08-10** and was not before. Four separate defects made a
re-deploy fail (see §9), which meant the first deploy worked and every subsequent one took the platform
down.

## 5. Networking — the one real change from ECS

Under ECS all containers shared a network namespace and reached each other on `localhost`. Under Compose
they are separate containers on a bridge network and reach each other **by service name**:

| From | To | URL |
|---|---|---|
| Caddy | BFF | `web-experience:8081` |
| Caddy | DPS | `dps:8090` |
| BFF | DAO | `https://dao:8443` |
| BFF | agent | `agent:8000` |
| DPS | BFF | `web-experience:8081` |

**This is why TLS verification is currently off.** The committed DAO keystore carries only a `localhost`
SAN, so `https://dao:8443` fails hostname verification. `dao_certificate_has_service_san` defaults false,
which sets `WEBE_DAO_TLS_VERIFICATION=false` — encrypted but not authenticated. Reissue the keystore with
a `dao` SAN and flip the variable.

Only 80 and 443 are open to the internet. The nine service ports have **no** security-group rules at all
— they are reachable only on the bridge, which has no route from outside.

## 6. Storage

| Data | Where | Why |
|---|---|---|
| Postgres | **RDS** `db.t4g.small` | Never on EFS: Postgres on NFS is a documented corruption risk, and EFS is slow for its fsync-heavy write pattern |
| Shared logs | EFS `/mnt/logs`, uid 1001 | Every service appends, so `rid` ties one browser action across the chain |
| Uploads | EFS `/mnt/assets`, uid 1001 | Must outlive the instance |
| Caddy certs | EFS `/mnt/caddy`, uid 0 | Survives replacement with no volume to attach; Let's Encrypt allows only 5 issuances per domain per week |
| Container logs | CloudWatch `/influencrm/prod` | One named stream per service |

The Caddy access point is **separate** because ownership differs: Caddy runs as root, every application
image as uid 1001, and an access point forces its `posix_user` onto every file. Sharing one would make
Caddy's certificate writes fail at runtime, after the container reported healthy.

EFS is what made the old EBS attach/detach sequencing — the source of the deadlock — disappear entirely.

## 7. Deploys

The image tag and compose file are baked into the launch template's user data, so a deploy is
`terraform apply` followed by an ASG instance refresh.

Two AWS limits shape this:

- **User data is capped at 16384 bytes after base64**, and the compose file is ~23KB. It lives in a
  versioned, KMS-encrypted S3 bucket the boot script fetches. Even then the script alone came to 16248
  bytes — 136 spare — so user data is `base64gzip`'d, which cloud-init decompresses transparently. It is
  now 6.4KB with ~10KB of headroom.
- **`min_healthy_percentage = 0`** on the refresh. With `max_size = 1` there is no spare capacity to
  keep a healthy instance alive during a roll, so this accepts a 3–5 minute gap rather than deadlocking
  on capacity that cannot exist — which is the ECS mistake in a different form.

## 8. Security posture — honestly

**What holds:**
- IMDSv2 required; hop limit 2.
- No SSH, no port 22, no bastion. Shell access is SSM Session Manager only.
- Secrets in Secrets Manager under a customer-managed KMS key, scoped by ARN.
- KMS-encrypted EBS, RDS, EFS, S3.
- Zero-trust service chain intact: the DAO rejects a request with no service token (verified — 401), and
  unauthenticated API calls are refused (verified — 401).

**What does not, and is not a bug you can file:**

1. **One IAM role does the work of two.** ECS had an execution role (pull images, read secrets) and a
   task role (what the app may do). Neither can be assumed without ECS. Containers now inherit the
   instance role through IMDS, so a process inside any container can read **every** secret the platform
   owns. Bounded by the hop limit and by the fact that the secrets are already in the container's
   environment — this widens reach, it does not create a new path.
2. **Secrets touch disk.** `/run/influencrm/platform.env`, root-only, tmpfs. ECS never wrote them down.
3. **TLS verification off between BFF and DAO** (§5).
4. **No WAF**, no alerting, no NAT gateway, no remote Terraform state.

## 9. What the smoke test found

Run end-to-end on 2026-08-10 against the deployed stack, from outside AWS. Signup returned **201** with a
real user, account and brand persisted to RDS plus a signed JWT; login returned the same `userId`; a
campaign was created and read back. Authenticated reads returned 200, unauthenticated 401.

It also found four defects. Three were fixed immediately:

- **`awslogs-stream-prefix` is an ECS option, not a Docker one.** Carried over from the task definition,
  it made the daemon refuse to create *any* container. Replaced with per-service `awslogs-stream`.
- **CloudFront rejects an IP origin.** With `api_domain` unset the origin fell back to the Elastic IP.
  The API origin is now conditional — and the condition tests for a *hostname*, not merely non-empty,
  which an IP passes.
- **User data over the 16KB limit** (§7).

The fourth was the migration, which needed four separate fixes and is the most interesting failure here:

| Defect | Why it only broke on the *second* deploy |
|---|---|
| Bare `create table` / `create type` / `add constraint` | Nothing exists on a fresh database, so the guards were never needed |
| `missing \|\| 'literal'` on a `text[]` | The append only executes when an index is found missing |
| Guards filtering `schemaname = 'public'` | Phase-5 moves those tables into context schemas, so a re-run looks in the wrong place |
| `if moved <> 24` | Later migrations add tables, so the exact count is only right once |
| **Unqualified DDL under a multi-schema `search_path`** | **The real root cause — see below** |

The last one subsumes the rest. `if not exists` is idempotent **per schema, not per database**: with
`search_path = identity,creator,…,public`, an unqualified `create table if not exists users` checks only
`identity`, finds nothing, and creates a *shadow* that takes precedence over the real table phase-5 moved
into a context schema.

Three re-runs left 21 shadow tables and 7 shadow enum types in the deployed database.
`identity.creators` had 33 columns against the real `creator.creators`'s 45, so `/api/creators` returned
502 — **while the migration reported success and every container reported healthy**. Health checks do
not exercise writes, and a migration that exits 0 is not the same as a migration that is correct.

Fixed by schema-qualifying all DDL (12 tables + 37 indexes + 7 enums + 7 triggers in the base schema,
20 tables across 9 migrations, 1 table + 4 indexes in the vector schema) and by correcting three guards
that resolved through `search_path` when they meant to ask about one specific schema.

Verified by rebuilding the test database from scratch and then redeploying **twice** against the
populated result: both clean, zero duplicates, full smoke test passing.

## 10. Cost

~$40/month: Spot `t3a.large` ~$22, RDS `db.t4g.small` ~$13, EFS ~$2, S3/CloudFront/Route 53 ~$3. The
Elastic IP is free while attached.

No NAT gateway (~$32 saved) and no ALB (~$17 saved) — the instance is public-subnet with a public IP, and
Caddy does what the load balancer would.

## 11. Where things are

| | |
|---|---|
| Terraform (test) | [`test/terraform/`](test/terraform/) |
| Production plan | [`prod/README.md`](prod/README.md) |
| ECS → Compose migration | [`COMPOSE-MIGRATION.md`](COMPOSE-MIGRATION.md) |
| Compose file template | [`test/terraform/templates/docker-compose.yml.tftpl`](test/terraform/templates/docker-compose.yml.tftpl) |
| Boot script template | [`test/terraform/templates/compose-boot.sh.tftpl`](test/terraform/templates/compose-boot.sh.tftpl) |
| Schema + migrations | [`../schema/`](../schema/) |

## 12. Operating it

```bash
# Shell on the host (no SSH exists)
aws ssm start-session --target <instance-id> --region us-east-1

# The stack
sudo docker compose -f /opt/influencrm/docker-compose.yml ps
sudo journalctl -u influencrm -f

# Logs
aws logs tail /influencrm/prod --follow --region us-east-1
aws logs tail /influencrm/prod --log-stream-names dao --follow --region us-east-1

# When it never comes up — the boot script writes to the serial console,
# which works before SSM is running
aws ec2 get-console-output --instance-id <id> --query Output --output text --region us-east-1
```
