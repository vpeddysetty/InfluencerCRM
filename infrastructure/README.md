# Infrastructure

Terraform for the application tiers on AWS Fargate, and the scripts that build and deploy into it.

**Status:** written and validated (`terraform validate` passes; `plan` evaluates every resource and
stops only at the STS call). **Nothing has been applied — no AWS resource exists from this code yet.**
There were no AWS credentials in this environment, so nothing could be created even in principle.

| Path | Contains |
|---|---|
| [terraform/](terraform/) | The whole environment: VPC, RDS, EFS, ECR, KMS, Secrets Manager, IAM, ECS, ALB, S3 + CloudFront |
| [scripts/build-and-push.sh](scripts/build-and-push.sh) | Builds all eleven images and pushes them to ECR under one tag |
| [scripts/bootstrap-secrets.sh](scripts/bootstrap-secrets.sh) | Generates the keystore and JWT key; prints the commands for the rest |
| [scripts/deploy-ui.sh](scripts/deploy-ui.sh) | Builds the seven micro-frontends and publishes each to its own origin |

Related: [../docs/infrastructure/containerization.md](../docs/infrastructure/containerization.md) for
the images themselves, and
[../docs/infrastructure/hosting-topology-decision.md](../docs/infrastructure/hosting-topology-decision.md)
for the SNI constraint that this deployment does not foreclose.

---

## The shape of it

**One Fargate task definition, eleven containers.** Containers in a task share a network namespace, so
they reach each other on `localhost:<port>` — no Service Connect, no Cloud Map, no mesh. Every
cross-service URL variable already defaults to `localhost`, so the task definition sets *fewer* of
them than `docker-compose.yml` does.

```
                     Internet
                        │
        ┌───────────────┼────────────────┐
        │                                │
   CloudFront × 7                       ALB
   (app, workflow, campaigns,      (80 → 443)
    creators, commerce,             │        │
    finance, content)          :8081 BFF   :8090 DPS
        │                           └────┬───┘
     S3 (one bucket,                     │
      one prefix each)          ┌────────▼─────────────────────────┐
                                │ ONE FARGATE TASK                 │
                                │                                  │
                                │  dao:8443 (HTTPS, self-signed)   │
                                │  web-experience:8081  ← ALB      │
                                │  dps:8090             ← ALB      │
                                │  workflow:8444   identity:8445   │
                                │  creator:8446    campaign:8447   │
                                │  attribution:8448 finance:8449   │
                                │  content:8450    agent:8000      │
                                │                                  │
                                │  all reach each other: localhost │
                                └───┬──────────────────────┬───────┘
                                    │                      │
                              RDS Postgres            EFS (2 access points)
                              (private, SG:task)      /logs   /assets
```

Nine of eleven containers have **no** target group, **no** listener rule and **no** security-group
ingress. They are reachable only over the task's loopback interface. That is what the single-task
shape buys: there is no network path to secure, rather than a path secured by configuration.

### No NAT gateway

The task runs in a **public subnet with a public IP** and egresses via the internet gateway. This
saves ~$32/month plus data processing over a NAT gateway.

A public IP is not the same as being reachable: the task's security group allows **no** inbound
traffic except from the ALB, on two ports. The public IP exists only for outbound calls, which the
task genuinely needs — ECR (to pull its own images), Secrets Manager, Google and Facebook OAuth, the
OpenAI API, and SES.

VPC endpoints were the alternative and are *not* cheaper here: ECR (×2), S3, Secrets Manager,
CloudWatch Logs and KMS at ~$7/month each exceeds the NAT gateway, and they would not carry the OAuth
or OpenAI traffic anyway.

`enable_nat_gateway = true` switches to a private subnet with a NAT; the code for it is already there
and creates nothing while the flag is false.

### One AZ

As decided. The ALB requires subnets in two AZs, so a second (empty) public subnet is created to
satisfy that — the task still runs in one. **An AZ outage takes the platform down**, and RDS cannot be
Multi-AZ. Setting `availability_zone_count = 2` makes the second subnet real and allows Multi-AZ, with
no other change.

---

## Deploying, in order

Each step depends on the one before it.

### 0. Credentials and permissions — **currently blocking**

```bash
export AWS_PROFILE=tejdux AWS_REGION=us-east-1   # no default profile is configured
./infrastructure/scripts/preflight.sh
```

`~/.aws/config` has a `tejdux` profile and **no default**, so without `AWS_PROFILE` every call fails
with `NoCredentials` — which reads like "not logged in" rather than "wrong profile".

**As of 2026-08-09 the preflight fails: 16 passed, 5 failed.** The `tejdux` user was scoped for the
static site and has no access to ECS, ELB, IAM, KMS or CloudWatch Logs. It also cannot grant itself
permissions, so **an admin or root identity must attach the two policies** in
[iam/README.md](iam/README.md) before anything here can be applied.

Two other things that preflight found and that must be done first:

- `AWSServiceRoleForECS` does not exist in the account. Fargate needs it, and the first
  `CreateService` fails with a message that does not name the cause.
- The only ACM certificate in us-east-1 is `www.tejdux.com`, **not a wildcard**. The seven
  micro-frontend subdomains need `*.tejdux.com`. Until it exists, leave both certificate variables
  empty and everything serves on its default AWS hostname.

### 1. State backend (do this before a second person ever runs apply)

State is **local** by default. A second person running `apply` would create a second copy of every
resource rather than seeing the first, and losing the file means Terraform no longer knows anything it
built. The S3 backend block is in [terraform/versions.tf](terraform/versions.tf), commented out
because a backend cannot be created by the configuration that uses it. Create the bucket, uncomment,
`terraform init -migrate-state`.

### 2. Infrastructure

```bash
cd infrastructure/terraform
terraform init
terraform apply -var image_tag=v1.0.0
```

`image_tag` is **required** and `latest` is rejected: a task definition must pin an immutable tag or a
rollback cannot be expressed. The first apply creates the ECR repositories among everything else — the
ECS service will not have images to pull yet, which is expected and fixed by step 3.

Takes ~15 minutes, most of it RDS.

### 3. Build and push the images

```bash
./infrastructure/scripts/build-and-push.sh v1.0.0
```

Repositories are **IMMUTABLE**, so pushing the same tag twice is rejected by the registry. Each image
is labelled with the tag and the git SHA (`--dirty` when the tree is not clean), so `docker inspect`
answers "what commit is this?" without a deployment log.

### 4. Populate the secrets

```bash
./infrastructure/scripts/bootstrap-secrets.sh prod us-east-1
```

**Three secrets block startup entirely** — the script generates all three:

| Secret | Without it |
|---|---|
| `jwt-signing-key` | The BFF **refuses to start**. Deliberate: tokens signed by an ephemeral key cannot be verified after a restart |
| `dao-keystore-b64` | The DAO **refuses to start** — `server.ssl.key-store` names a file that is not there |
| `dao-keystore-password` | As above |

> The script also writes a fresh `dao-truststore.p12` into the BFF's resources. **Commit it and
> rebuild the BFF image** — otherwise the BFF still trusts the old certificate and every DAO call
> fails verification, fail-closed.

Everything else fails *closed* — the feature is unavailable and says so — so the platform runs without
them. The script prints the exact command for each. The most consequential:

- `openai-api-key` — **rotate the key currently in `.env` first.** It is a live key that sat in the
  working tree.
- `ses-*` — until set, the email provider stays `log`: invitations reach **nobody** and nothing on
  screen says so.
- `stripe-secret-key` — until set, billing uses the `manual` provider, which takes **no money**.

A secret change is only picked up when a task starts:

```bash
aws ecs update-service --cluster influencrm-prod-cluster \
  --service influencrm-prod-platform --force-new-deployment
```

### 5. Database schema

The containerized stack creates its schema from `schema/` init scripts, which only run on an **empty**
data directory. RDS does not use them. Apply the schema once, from a machine that can reach the
instance:

```bash
psql "postgresql://influencercrm_user@$(terraform output -raw database_endpoint):5432/influencercrm_db" \
  -f schema/influencer_crm_schema.sql
# then every migration in schema/migrations/, in filename date order — see
# schema/zzz_apply_migrations.sql for the authoritative list and the ordering constraint
```

RDS is not publicly accessible, so this needs a bastion, a VPN, or a one-off task in the VPC. **This
is the one manual step with no automation, and it is the most likely thing to be got wrong** — a
missing migration presents as a service failing on first query against a table that does not exist.

### 6. The UIs

```bash
./infrastructure/scripts/deploy-ui.sh
```

Reads the bucket, distribution ids and remote origins from Terraform outputs, so step 2 must be done.

---

## Micro-frontends: why subdomains, not paths

The obvious design — one distribution, `/workflow/`, `/campaigns/`, … — **does not work here**, and it
is worth knowing why before someone tries to simplify it.

1. Each remote's built `index.html` references assets **absolutely**. Verified in
   `InfluencerWorkflowUI/dist/index.html`: `src="/assets/mf-entry-bootstrap-0-….js"`. Served under
   `/workflow/`, that request goes to `/assets/…` at the domain root — the *shell's* asset directory.
2. The shell resolves each remote as `${origin}/remoteEntry.js`
   ([originRegistry.js](../InfluencerUI/src/shell/gateway/originRegistry.js)), so the entry must be at
   the **root** of whatever origin it is given.

Fixing (1) with vite `base` would also move `remoteEntry.js`, requiring the registry to carry paths
rather than origins — a change to the shell's contract. Subdomains keep the registry exactly as
designed:

| Subdomain | Project | Shell env var |
|---|---|---|
| `app` | InfluencerUI (the shell) | — |
| `workflow` | InfluencerWorkflowUI | `VITE_MF_WORKFLOW_ORIGIN` |
| `campaigns` | InfluencerCampaignsUI | `VITE_MF_CAMPAIGNS_ORIGIN` |
| `creators` | InfluencerCreatorsUI | `VITE_MF_CREATORS_ORIGIN` |
| `commerce` | InfluencerCommerceUI | `VITE_MF_COMMERCE_ORIGIN` |
| `finance` | InfluencerFinanceUI | `VITE_MF_FINANCE_ORIGIN` |
| `content` | InfluencerContentUI | `VITE_MF_CONTENT_ORIGIN` |

One bucket holds all seven, one prefix each; each distribution's **origin path** makes its own prefix
look like its root, so the absolute `/assets/…` references resolve with no rebuild.

**These origins are build-time.** Vite inlines `import.meta.env.VITE_*`, so the shell's federation map
is frozen into its bundle: moving a remote means rebuilding and redeploying the shell.
`deploy-ui.sh` writes `.env.production` from the Terraform outputs so the two cannot drift, and builds
the remotes **before** the shell.

**CORS is the thing most likely to break a federated deployment that looks correct.** A remote is
executable code fetched cross-origin; without `Access-Control-Allow-Origin` the browser blocks
`remoteEntry.js` and the shell renders an empty route with only a console error. The response headers
policy handles it, and the cache key includes `Origin` so a cached response cannot serve the wrong
header.

### www.tejdux.com is not touched

That site **already exists** and is not managed by Terraform — bucket `tejdux-legal-static`,
distribution `ESJ9LTY0C74G0`, built by hand on 2026-08-05 and recorded in
[../docs/infrastructure/static-site-deployment-log.md](../docs/infrastructure/static-site-deployment-log.md).
It serves `/terms/` and `/privacy/`, which are linked from an app-store listing.

Importing it would mean reconstructing its exact current state in code first, and getting that wrong
takes live legal pages down. So this configuration creates a **separate** bucket and its own
distributions, and the two coexist. Adopting the legal site later is a contained job: `terraform
import` the four resources and reconcile until the plan is empty.

The landing page at `www.tejdux.com/` is currently **unpublished and returns 403** — see the existing
runbook to add one. This configuration does not change that.

---

## Things to know before this is a production deployment

Ordered by how much they would hurt.

1. **`desired_count` is validated to 1, and that is load-bearing.** The DPS holds sessions in its own
   heap while Redis is out of scope, so a second task serves a disjoint set of sessions and users get
   logged out whenever the balancer moves them. Bring back Redis/ElastiCache *and* remove the
   validation together — and revisit ALB stickiness at the same time.
2. **Sessions die on every redeploy**, for the same reason. Every signed-in user is logged out.
3. **Schema application is manual** (step 5). No migration runner exists for RDS.
4. **The svc_\* database passwords are still `change-me-<ctx>`**, the defaults from the context-roles
   migration. They are reachable only from inside the task, but rotating them means an `ALTER ROLE`
   migration plus a secret per role — deliberately not attempted here, because a half-rotated set
   would leave services unable to connect with no obvious cause.
5. **SES still uses access keys, not the task role.** The task role holds `ses:SendEmail`, but
   `SesEmailSender` reads explicit keys from configuration, so the role alone is not used. Switching
   the adapter to the default credentials provider is a small code change and removes two long-lived
   secrets.
6. **One AZ, single task**: no redundancy of any kind. An AZ event, or one essential container OOMing,
   is a full outage.
7. **`enable_deletion_protection = false` on the ALB.** RDS *does* have deletion protection and a
   final snapshot, so `terraform destroy` will fail on the database until you explicitly disable it —
   that is intentional.

## Cost, roughly

| Item | Monthly |
|---|---|
| Fargate 2 vCPU / 8 GB, 1 task, always on | ~$72 |
| RDS db.t4g.small, single-AZ, 20 GB gp3 | ~$25 |
| ALB | ~$17 |
| EFS (few GB, bursting) | ~$1 |
| CloudFront + S3 (low traffic) | ~$2 |
| Secrets Manager (19 secrets @ $0.40) | ~$8 |
| KMS key | $1 |
| **NAT gateway (avoided)** | **$0** (~$32 if enabled) |
| | **~$126** |

Container Insights `enhanced` and RDS Performance Insights add a few dollars and are worth keeping —
without them a performance question has no data behind it.
