# Production infrastructure — not built yet

This directory is intentionally empty of Terraform. Production is on the roadmap for the **subscription
tier launch**, and building it before there is a paying user to justify it would mean paying for
resilience nobody is using yet.

Everything running today is in [`../test/terraform`](../test/terraform). That environment is deliberately
cheap and deliberately fragile — see [../test/terraform/README.md](../test/terraform/README.md) for what
it gives up to cost ~$40/month.

**Do not promote the test configuration by copying it here and changing `environment = "prod"`.** The
things that make it cheap are the same things that make it unsuitable for paying customers, and they are
not tunable by variable — they are architectural.

## What production has to change, and why

Each row is something the test environment genuinely cannot do, not a setting someone forgot.

### 1. More than one instance

| | Test | Prod |
|---|---|---|
| Instances | 1 Spot, `max_size = 1` | ≥2 On-Demand or mixed, across AZs |
| Session store | Caffeine, in JVM heap | Redis / ElastiCache |
| Load balancing | Caddy on the instance | ALB with health checks |
| DNS | A record to an Elastic IP | ALIAS to the ALB |

These four are **one change, not four**. The test environment is pinned to a single instance *because*
DPS holds sessions in the heap: a second instance would log users out at random as requests landed on the
wrong one. Adding Redis is what unlocks the second instance, which is what makes an ALB meaningful, which
is what removes the Elastic IP. Doing any one alone gains nothing.

The single instance is also the whole availability story today: a Spot reclamation is a full outage of
3–5 minutes, and there is no second instance to carry traffic.

### 2. Deploys without downtime

Test runs `docker compose up`, which restarts in place — the platform is down for as long as the JVMs
take to boot. There is no way around that with one instance and no orchestrator.

Production needs a rolling deploy, which means a scheduler that can run two versions at once: ECS (with
the capacity provider actually configured for it), EKS, or an ASG instance refresh with `min_healthy > 0`.
Note that ECS is what the test environment moved *away* from — see [../COMPOSE-MIGRATION.md](../COMPOSE-MIGRATION.md)
for the deadlock that caused. The lesson there is about a single-instance deployment fighting a scaler,
not about ECS being wrong for a multi-instance one.

### 3. Real credential isolation

Test collapsed the ECS execution and task roles into **one instance role**, because without ECS neither
could be assumed. Containers inherit it through IMDS, so a process that gets into any container can read
every secret the platform owns.

Production needs the separation back: per-service credentials, so the agent container cannot read the
Stripe key and the content service cannot read the DAO keystore. That means either a real orchestrator
with per-task roles, or a sidecar vending scoped tokens.

### 4. Database durability

| | Test | Prod |
|---|---|---|
| RDS | `db.t4g.small`, single-AZ | Multi-AZ with a standby |
| Backups | 7-day automated | 30-day + cross-region snapshot copy |
| Failover | manual, minutes of downtime | automatic |
| Deletion protection | off | **on** |

Single-AZ RDS means an AZ failure is an outage until AWS restores the instance. That is acceptable for a
test environment and not for a paying one.

### 5. The things test has none of

- **No WAF.** The instance's security group is open on 80/443 to `0.0.0.0/0` with nothing inspecting the
  traffic.
- **No alerting.** Logs go to CloudWatch; nothing pages anyone when they stop arriving. The first signal
  that the platform is down is a user noticing.
- **No NAT gateway.** Instances sit in a public subnet with public IPs because that is the only route to
  ECR without paying ~$32/month for NAT. Production should have private subnets and a NAT.
- **No remote state.** Terraform state is a local file — no locking, so two people applying at once
  corrupt it, and no history if the file is lost. Production needs S3 + DynamoDB locking **before** the
  first apply, not after.
- **No staging step.** Test *is* the only environment, so there is nowhere to rehearse a change.

## Rough cost

Test is ~$40/month. Production as described lands around **$250–350/month** before traffic: two
on-demand instances (~$120), Multi-AZ RDS (~$60), ALB (~$17), ElastiCache (~$25), NAT (~$32), plus WAF,
backups and cross-region copies.

That gap is the honest reason production is not built yet.

## Before the first production apply

1. **Remote state with locking** — S3 bucket + DynamoDB table, created before anything else.
2. **A separate AWS account.** Test currently runs in `099933382956`. Production sharing an account
   means one bad `terraform destroy` reaches both.
3. **Fix what test documented but left open** — the DAO certificate still has no `dao` SAN, so TLS
   verification between BFF and DAO is disabled. Production must not ship that way. See
   [../COMPOSE-MIGRATION.md](../COMPOSE-MIGRATION.md).
