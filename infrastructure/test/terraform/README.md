# Test environment — Terraform

This is the **only** environment that exists today, and it is deliberately a test environment: cheap,
single-instance, and fragile in ways that are documented rather than accidental. Production is on the
roadmap for the subscription-tier launch — see [../../prod/README.md](../../prod/README.md) for what it
has to change and why none of it is a variable flip.

Full architecture: [../../ARCHITECTURE.md](../../ARCHITECTURE.md).

## Running it

```bash
cd infrastructure/test/terraform
terraform init
terraform plan  -var=image_tag=v1.0.0 -out=tf.plan
terraform apply tf.plan
```

`image_tag` is required and **rejects `latest`** — an immutable tag is what makes a rollback expressible.

Deploying a new image or a changed compose file, both of which live in the launch template's user data:

```bash
terraform apply -var=image_tag=v1.0.1
aws autoscaling start-instance-refresh \
  --auto-scaling-group-name "$(terraform output -raw autoscaling_group_name)" \
  --region us-east-1
```

## What is here

| File | What it holds |
|---|---|
| `compose-ec2.tf` | The platform: instance role, security group, launch template, ASG, config bucket, log group |
| `compose-support.tf` | Elastic IP, `use_rds`, Spot instance types, ACME email |
| `network.tf` | VPC, public subnets, RDS and EFS security groups |
| `data.tf` | RDS Postgres, EFS filesystem and access points |
| `secrets.tf` | KMS key, ~20 Secrets Manager secrets |
| `ecr.tf` | 12 image repositories |
| `static-site.tf`, `static-site-cdn.tf` | UI bucket, 7 CloudFront distributions, Route 53 |
| `iam.tf` | A comment explaining where the ECS roles went — see below |
| `templates/` | The rendered compose file and instance boot script |

## The three things most likely to surprise you

**1. `iam.tf` contains no resources.** The ECS execution and task roles were deleted with ECS, since both
trusted `ecs-tasks.amazonaws.com` and nothing can assume them any more. One instance role in
`compose-ec2.tf` does both jobs, which means containers inherit its credentials via IMDS and can read
every secret. The file is kept as a comment because "where did the task role go" is the first question
anyone reading `compose-ec2.tf` will ask.

**2. `max_size = 1` is load-bearing.** Not a cost setting. DPS holds sessions in the JVM heap, so a second
instance logs users out at random; and one Elastic IP attaches to one instance. An earlier ECS deployment
deadlocked precisely because its capacity provider kept trying to run two instances against a design that
assumed one — see [../../COMPOSE-MIGRATION.md](../../COMPOSE-MIGRATION.md).

**3. The compose file is in S3, not user data.** User data is capped at 16384 bytes after base64
encoding, and the compose file is ~23KB. The boot script fetches it at startup, and user data itself is
gzipped (`base64gzip`) so the script has ~10KB of headroom instead of 136 bytes.

## State

Local files (`terraform.tfstate`). No remote backend, no locking — two concurrent applies corrupt it, and
losing the file means importing 65 resources by hand. Acceptable for one operator on one environment;
production needs S3 + DynamoDB **before** its first apply.

## Known-open items

Both are documented in [../../COMPOSE-MIGRATION.md](../../COMPOSE-MIGRATION.md) and neither is fixed:

- **`dao_certificate_has_service_san = false`.** Under Compose the BFF reaches the DAO at `https://dao:8443`
  rather than `localhost`, and the committed keystore only carries a `localhost` SAN — so TLS
  verification is **off**. Encrypted, but not authenticated. Reissue the keystore with a `dao` SAN and
  flip the variable.
- **`api_domain` is unset.** Caddy therefore serves plain HTTP on the Elastic IP: no Let's Encrypt
  certificate (an IP cannot be certified), no OAuth sign-in (providers reject an IP redirect URI), and
  the CloudFront distributions serve the UI only — `/api/*` and `/dps/*` do not route through them,
  because CloudFront rejects an IP as an origin.
