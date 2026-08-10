# Deployment state — 2026-08-10

**Where it stands: infrastructure applied, images pushed, the ECS task is NOT yet running.**
One known-cause blocker remains, with the fix already applied and verified. Steps 1-3 below are what
is left.

## What is deployed and verified

| Thing | State |
|---|---|
| Terraform | `Apply complete` — 136 resources, account `099933382956`, us-east-1 |
| All 12 images | In ECR at `v1.1.0` (11 services + `migrate`) |
| Elastic IP | `100.58.135.154` — the platform's public address |
| CloudFront | 7 distributions, one per micro-frontend (see `terraform output ui_urls`) |
| ECS cluster + service | `influencrm-prod-cluster` / `influencrm-prod-platform`, ACTIVE |
| Spot ASG | 1 × t3a.large, instance `i-091186de6d0bf0421` |
| EBS data volume | `vol-0d048f198ee68bc5b`, 30GB, encrypted — **attaches correctly as of the KMS fix** |

Cost: **~$32/month** (Spot ~$22, EBS ~$3, EIP ~$3.60 when idle, secrets ~$8, S3/CloudFront ~$2).
No ALB, no RDS, no NAT gateway, no Fargate.

## The remaining work

### 1. Re-run the instance boot script, or replace the instance
The boot script failed on its FIRST run because the KMS key policy did not yet grant EBS access, so
the volume never attached and the script exited before joining the ECS cluster. The KMS policy is now
fixed and the attach was verified by hand.

Simplest path — let the ASG rebuild from the corrected state:
```bash
export AWS_PROFILE=tejdux AWS_REGION=us-east-1 MSYS_NO_PATHCONV=1
aws ec2 detach-volume --volume-id vol-0d048f198ee68bc5b            # if still attached
aws autoscaling terminate-instance-in-auto-scaling-group \
  --instance-id i-091186de6d0bf0421 --no-should-decrement-desired-capacity
# Then watch it come up:
aws ecs list-container-instances --cluster influencrm-prod-cluster   # expect 1, not 0
```

### 2. Populate the three startup-blocking secrets
```bash
./infrastructure/scripts/bootstrap-secrets.sh prod us-east-1
```
Generates the DAO keystore and the JWT signing key. **Without them the DAO and BFF refuse to start** —
deliberately. It also writes a fresh `dao-truststore.p12` into the BFF resources, which then needs a
**rebuild and push of the BFF image at a new tag** (ECR tags are immutable, so `v1.1.1`, not `v1.1.0`).

### 3. Watch the task reach healthy
```bash
aws logs tail /ecs/influencrm-prod --follow
```
Order is `postgres` (healthy) → `migrate` (must exit 0) → `dao`/`bff`/`dps`/`agent` → `caddy`.
**The migration container is the gate**: it applies the whole schema and verifies it, and every app
container waits on `migrate: SUCCESS`. If it exits non-zero the schema is incomplete and nothing else
starts — which is the intended behaviour, not a bug.

## Bugs found and fixed during this deployment

Each was a real defect in my own configuration, found by an apply rather than by review:

| Failure | Cause | Fix |
|---|---|---|
| 13 × `You must provide either SecretString or SecretBinary` | Secrets Manager rejects `""`; my "empty means unconfigured" design is invalid at the API | Placeholder is a single space |
| `CreateLogGroup … KMS key does not exist or is not allowed` | Writing a key policy REPLACES the default root grant; CloudWatch Logs was then implicitly denied | Added a `logs.*` statement scoped by encryption context |
| **`CustomerKeyHasBeenRevoked` on AttachVolume** | Same root cause — the new key policy also revoked EBS | Added EBS + autoscaling statements scoped by `kms:ViaService` |
| `ClientException: Too many containers` | **ECS caps a task at 10 containers**; I had 14 | Dropped the 7 extracted context services (see below) |
| `Fargate compatible task definitions do not support sourcePath` | `requires_compatibilities` included FARGATE, which forbids `host_path` | EC2-only |
| `Assign public IP is not supported for this launch type` | `assign_public_ip` is Fargate-only; on EC2 the instance carries the IP | Null on EC2 + `associate_public_ip_address` in the launch template |
| 4 × `Invalid rule description` | EC2 permits only `a-zA-Z0-9._-:/()#,@[]+=&;{}!$*` — no apostrophes, em-dashes or newlines | Single-line ASCII descriptions |
| Instance had no public IP → `dial tcp i/o timeout` | It launched from launch-template v1, before the public-IP fix | Terminated; ASG rebuilds from `$Latest` |

## The 7 extracted services are NOT deployed

`workflow, identity, creator, campaign, attribution, finance, content` — dropped to fit the
10-container ECS limit. **This costs no functionality today**: the BFF has
`WORKFLOW_SERVICE_ENABLED=false` and no `*_SERVICE_URL` routing enabled, so it already serves every
domain from the monolith DAO. Those containers would have run, passed health checks, and received zero
traffic.

Their images are still in ECR, and `local.context_containers` is still defined in `ecs.tf`. Re-enabling
one means its own task definition and service, plus ECS Service Connect — because a second task means
cross-task calls, and `localhost` stops being the answer.

## Not started

- **UI deploy.** `./infrastructure/scripts/deploy-ui.sh` — needs the app running first, since the shell
  bakes in `VITE_BFF_URL` at build time.
- **E2E journeys.** The suite at `tests/e2e/` has 9 journeys and already supports `E2E_BASE_URL` and
  video recording, so it can point at AWS unchanged. It does **not** yet cover the three you asked for:
  spreadsheet import, the kanban workflow, and landing page + coupon. Those need writing.
- **Release tag.** To be pushed once deployment and E2E both pass.

## Two things to fix regardless

- **The OpenAI key in `.env` is live and was in the working tree. Rotate it**, then put the new value in
  `influencrm-prod/openai-api-key`.
- **`AdministratorAccess` is attached to `user/tejdux`**, a long-lived static key on this machine. The
  two least-privilege policies in `infrastructure/iam/` are written and validated — attach those and
  detach admin once deploying is done.

## Shell gotcha that cost real time

Git Bash rewrites `/dev/sdf` into `C:/Program Files/Git/dev/sdf` and mangles `/aws/service/...` SSM
paths. **Export `MSYS_NO_PATHCONV=1`** for any AWS CLI call containing an absolute path. Also export
`PYTHONUTF8=1`, or the CLI throws `'charmap' codec can't encode` on output containing non-ASCII.
