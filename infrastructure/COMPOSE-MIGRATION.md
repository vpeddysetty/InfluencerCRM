# From ECS to Docker Compose on one Spot instance

## Why

The ECS deployment never ran a task. It failed like this:

```
(service influencrm-prod-platform) was unable to place a task.
Reason: TaskFailedToStart: EMPTY CAPACITY PROVIDER.
```

Two EC2 instances were running; **zero** were registered with the cluster. The cause was a deadlock over
a single EBS volume:

- `i-091186de6d0bf0421` held `influencrm-prod-postgres-data`, but the boot script's device-discovery loop
  never matched the volume with `nvme id-ctrl`. After 30 attempts it hit
  `FATAL: data volume never appeared as a device` and exited 1. cloud-init's user-data module failed, so
  the ECS agent never started and the instance never registered.
- `i-0b67b242054414495` waited 60 attempts for the volume to become `available`. It never would —
  instance A was holding it and was not going anywhere.

The ASG was at `desired = 2` because the capacity provider's managed scaling kept raising it to place a
task it could not place. But the boot script assumed exactly **one** owner of the volume. The
architecture and its scaling policy disagreed, and the disagreement was unresolvable.

Tuning the capacity provider would not fix this. The fix is to delete the component that wants to scale,
because this deployment can only ever have one instance:

- DPS holds sessions in-heap (Redis is out of scope), so a second instance logs users out at random.
- One Elastic IP is the DNS target, and it attaches to one instance.

## What changed

| | Before | After |
|---|---|---|
| Orchestration | ECS cluster + service + task definition + capacity provider | `docker compose` under systemd |
| Postgres | Container on an attached EBS volume | **RDS** `db.t4g.micro` |
| Shared logs / assets | EFS (2 access points) | EFS, unchanged |
| Caddy certificates | Host path on the EBS volume | **EFS** (new access point) |
| Instance count | min 1 / desired 2 / max 2 | min 1 / desired 1 / **max 1** |
| Secrets | Injected by the ECS execution role | Fetched by the instance into `/run/influencrm/platform.env` |
| Container networking | One namespace, `localhost:<port>` | Bridge network, **service names** |
| Container limit | 10 (ECS cap) | none — all 13 expressible |
| Shell access | `aws ecs execute-command` | SSM Session Manager to the host |

`max_size = 1` is the structural fix: the ASG *cannot* produce a second instance even if something asks
it to. And with Postgres on RDS there is no volume to contend for in the first place — the instance is
now stateless, which is the entire point of running it on Spot.

## Files

**Added**
- `terraform/compose-ec2.tf` — instance role, security group, launch template, ASG, log group, Caddy EFS access point
- `terraform/templates/docker-compose.yml.tftpl` — all 13 services
- `terraform/templates/compose-boot.sh.tftpl` — EFS mounts, EIP claim, secret fetch, systemd units

**Deleted**
- `terraform/ecs.tf`, `ecs-ec2-spot.tf`, `ecs-containers-extra.tf`, `postgres-ebs.tf`, `alb.tf`
- `terraform/templates/instance-boot.sh.tftpl`

**Rewritten**
- `terraform/iam.tf` — now a comment explaining where the execution and task roles went
- `terraform/compose-support.tf` (was `alb-toggle.tf`) — EIP, `use_rds`, spot variables, `acme_email`

## BLOCKER: the migration is not idempotent

**Found by the smoke test, and it will take the platform down on your next deploy.**

The ECS config asserted "every migration in this repo is idempotent," and it is not. On a second run
against an already-built schema, `migrate` fails:

```
psql:/schema/influencer_crm_schema.sql:34: ERROR:  relation "users" already exists
FATAL: influencer_crm_schema.sql failed. The schema is INCOMPLETE; do not start the services.
```

Because every application service declares `depends_on: migrate: service_completed_successfully`, a
failed migration means **nothing starts at all**. The first deploy works (empty database); every
subsequent one takes the whole platform down. This is not caused by the move off ECS — ECS would have
hit it identically on the second deploy — but the move is what surfaced it.

`schema/influencer_crm_schema.sql` uses bare `CREATE TABLE`. The extension creation is already guarded
with `IF NOT EXISTS`, so the pattern is established; the table and type DDL just needs the same
treatment. Until that is fixed, a redeploy needs:

```bash
docker compose --env-file /run/influencrm/platform.env up -d --no-deps dao web-experience dps agent caddy
```

which is what the smoke test used to recover, and is exactly the sort of manual step this deployment
should not need.

## Two things you must do

### 1. The DAO certificate needs a `dao` SAN

Under ECS, containers shared a network namespace, so the BFF called `https://localhost:8443` and the
committed keystore's `localhost` SAN matched. Under Compose they are separate containers and the URL is
`https://dao:8443` — a certificate carrying only `localhost` **fails hostname verification on every
call**.

`var.dao_certificate_has_service_san` defaults to `false`, which sets `WEBE_DAO_TLS_VERIFICATION=false`.
The connection stays encrypted but stops verifying who is on the other end. That is a real reduction in
the zero-trust chain, acceptable only because both ends are containers on one host's private bridge.

**Reissue the keystore with both `dao` and `localhost` as SANs, then set the variable true.** This should
not be left undone.

### 2. IAM separation is lost

ECS had an execution role (pull images, read secrets) and a task role (what the app may do). Neither can
be assumed now — both trusted `ecs-tasks.amazonaws.com`. One instance role does both jobs, and the
containers inherit its credentials through IMDS.

A process that reaches IMDS from inside a container can now read every secret this platform owns. Two
things bound it: `http_put_response_hop_limit = 2`, and the fact that the secrets are already in the
container's environment anyway — so this widens what an attacker reaches rather than handing them a new
path. Restoring the split means a control plane again, or a sidecar vending scoped tokens. Neither is
worth it at one instance; both become worth it at more than one, alongside Redis and an ALB.

## What this costs

- **No rolling deploy.** `compose up` restarts in place: ~3-5 minutes of downtime. ECS at
  `desired_count = 1` on one instance had the same gap, so this is not a regression.
- **No control-plane task replacement.** `restart: unless-stopped` plus health checks covers a crashed
  container; it does not cover a wedged one.
- **Secrets on disk.** Root-only, mode 0600, on tmpfs (`/run`), so they do not survive a reboot and are
  re-fetched each boot. ECS never wrote them down at all.
- **RDS `db.t4g.micro`** adds ~$13/month. In exchange: automated backups, PITR, and a stateless instance.

## Deploying

```bash
cd infrastructure/terraform
terraform plan -var=image_tag=v1.0.0 -out=compose.tfplan
terraform apply compose.tfplan
```

RDS takes ~10 minutes to create on first apply. The `migrate` container builds the schema on first boot,
so the empty database is expected — every application service waits on
`service_completed_successfully` before it starts.

To deploy a new image tag afterwards (the compose file and tag are baked into user data):

```bash
terraform apply -var=image_tag=v1.0.1
aws autoscaling start-instance-refresh --auto-scaling-group-name <name> --region us-east-1
```

## Debugging

There is no `aws ecs execute-command`. The way in is the host:

```bash
aws ssm start-session --target <instance-id> --region us-east-1
sudo docker compose -f /opt/influencrm/docker-compose.yml ps
sudo journalctl -u influencrm -f
sudo cat /var/log/influencrm-boot.log
```

Before SSM is up — which is exactly when the interesting failures happen — the boot script writes to the
serial console:

```bash
aws ec2 get-console-output --instance-id <id> --query Output --output text --region us-east-1
```

## The seven context services

All seven are in the compose file under the `contexts` profile, so `docker compose up -d` does **not**
start them and the default footprint stays the measured ~4.2GB. They were dropped from the ECS task
because of the ten-container cap; Compose has no such cap, so re-enabling one is now a profile plus the
BFF's routing flag rather than a new task definition and a service mesh.

Starting all seven adds ~2.7GB and does **not** fit on a `.large`. That needs an `.xlarge` in
`var.spot_instance_types`.

## End-to-end smoke test (2026-08-10, against the deployed stack)

Run from outside AWS against the Elastic IP, plus SSM into the instance. Every layer touched.

| # | Integration point | Result |
|---|---|---|
| 1 | Caddy edge, public :80 | reachable from the internet |
| 2 | BFF `actuator/health` | 200 `{"status":"UP"}` |
| 3 | DAO `/health` over TLS | 200 |
| 4 | DPS `actuator/health` | 200 |
| 5 | Agent (FastAPI) `/docs` | 200 |
| 6 | **BFF → DAO at `dao:8443`** | 200 — the bridge-network change works |
| 7 | Schema on RDS | 8 context schemas, 8 `svc_*` roles, 6 late tables, 2 idempotency indexes |
| 8 | **Signup** `POST /api/auth/signup` | **201** — user + account + brand persisted, JWT issued |
| 9 | **Login** with same credentials | 200, same `userId` — read back from RDS |
| 10 | **Create campaign** `POST /api/campaigns` | **200**, persisted |
| 11 | **List campaigns** | 200, returns the created row — full round trip |
| 12 | Authenticated reads (`/api/creators`, `/api/brands`, `/api/campaign-creators`, `/api/import-batches`) | 200 |
| 13 | Unauthenticated `/api/campaigns` | **401** — authz enforced |
| 14 | DAO without a service token | **401** — zero-trust chain intact |
| 15 | EFS `/mnt/logs` | services writing `influencrm-{dao,dps,web-experience}.log` as uid 1001 |
| 16 | EFS `/mnt/caddy` | Caddy cert store written as root |
| 17 | CloudWatch | one named stream per service |

Two bugs were found and fixed during the test:

- **`awslogs-stream-prefix` is ECS-only.** The Docker daemon rejects it outright and refused to create
  any container: `unknown log opt 'awslogs-stream-prefix'`. Replaced with a per-service `awslogs-stream`,
  which also gives readable stream names instead of container-ID hashes.
- **CloudFront rejects an IP origin.** The first fix tested `!= ""`, but the IP *is* non-empty — the test
  now checks the value is a hostname.

One defect was found and **not** fixed: the non-idempotent migration above.

## Verification performed

- `terraform validate` — passes
- `terraform plan` — 19 add / 10 change / 38 destroy, saved to `compose.tfplan`
- `docker compose config` on the rendered file — accepted; all 13 services, dependencies and profiles correct
- `bash -n` on the rendered boot script and the embedded spot watcher — both parse
- Confirmed safe to destroy: the backups bucket is **empty** and the Postgres EBS volume **never held
  data** (Postgres never started — that was the deadlock)
- Confirmed surviving untouched: EFS filesystem, both existing access points, the Elastic IP, and all
  22 secrets
