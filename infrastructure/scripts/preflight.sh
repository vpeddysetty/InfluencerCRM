#!/usr/bin/env bash
# Check that the logged-in identity can actually perform the deployment, BEFORE running apply.
#
#   AWS_PROFILE=tejdux AWS_REGION=us-east-1 ./infrastructure/scripts/preflight.sh
#
# THE PROFILE IS NOT OPTIONAL. ~/.aws/config defines a `tejdux` profile but no default, so without
# AWS_PROFILE every call fails with NoCredentials — which reads like "not logged in" rather than
# "logged in, wrong profile selected". Export it, or pass --profile to everything.
#
# WHY THIS EXISTS. A `terraform apply` that stops two-thirds of the way through on an AccessDenied is
# the worst outcome available: the environment is half-built, some resources are billing, and the fix
# needs a permission grant followed by a re-apply that has to reconcile partial state. Ten seconds of
# dry-run checks up front avoids that.
#
# HOW IT CHECKS. Read-only calls plus IAM policy SIMULATION — `iam simulate-principal-policy` asks
# "would this be allowed?" without doing it. That is the only honest way to test a destructive
# permission like rds:DeleteDBInstance. Where simulation is not available for a principal type
# (assumed roles from SSO cannot always be simulated), it falls back to a harmless read per service
# and says so, because a read succeeding does NOT prove a write would.
#
# Exit 0 = clear to apply. Exit 1 = something would fail; the output says what and which policy to fix.
set -uo pipefail   # NOT -e: every check must run so the report is complete, not truncated at the first failure.

REGION="${1:-${AWS_REGION:-us-east-1}}"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
PASS=0; FAIL=0; WARN=0

ok()   { printf "  ${GREEN}PASS${OFF}  %s\n" "$1"; PASS=$((PASS+1)); }
bad()  { printf "  ${RED}FAIL${OFF}  %s\n" "$1"; FAIL=$((FAIL+1)); }
warn() { printf "  ${YELLOW}WARN${OFF}  %s\n" "$1"; WARN=$((WARN+1)); }
head_() { printf "\n${BOLD}%s${OFF}\n" "$1"; }

# ---------------------------------------------------------------------------
# 0. Who are we?
# ---------------------------------------------------------------------------
head_ "Identity"

IDENTITY_JSON="$(aws sts get-caller-identity --output json 2>&1)"
if ! echo "$IDENTITY_JSON" | grep -q '"Arn"'; then
    printf "  ${RED}FAIL${OFF}  no usable credentials\n\n%s\n\n" "$IDENTITY_JSON"
    echo "Log in first (aws login / aws configure / SSO), then re-run."
    exit 1
fi

ARN="$(echo "$IDENTITY_JSON" | python -c 'import json,sys; print(json.load(sys.stdin)["Arn"])')"
ACCOUNT="$(echo "$IDENTITY_JSON" | python -c 'import json,sys; print(json.load(sys.stdin)["Account"])')"
ok "$ARN"
ok "account $ACCOUNT, region $REGION"

# The account the existing static site and Route 53 zone live in. A different account is not
# necessarily wrong, but it does mean the hosted-zone lookup and the ACM certificate ARNs in
# example.tfvars will not resolve — and that is worth knowing before apply, not during.
if [ "$ACCOUNT" != "099933382956" ]; then
    warn "expected account 099933382956 (where tejdux.com and the ACM certs live, per docs/infrastructure/README.md)"
    warn "  a Route 53 / ACM lookup will fail unless root_domain and the cert ARNs are changed"
fi

# For simulate-principal-policy we need the ARN of the *principal* (user or role), not the assumed
# session. An assumed-role ARN has to be converted back to the role ARN.
POLICY_SOURCE="$ARN"
case "$ARN" in
    *:assumed-role/*)
        ROLE_NAME="$(echo "$ARN" | cut -d/ -f2)"
        POLICY_SOURCE="arn:aws:iam::${ACCOUNT}:role/${ROLE_NAME}"
        ;;
esac

# ---------------------------------------------------------------------------
# 1. Can we simulate at all?
# ---------------------------------------------------------------------------
head_ "Permission checks"

SIMULATE=true
if ! aws iam simulate-principal-policy \
        --policy-source-arn "$POLICY_SOURCE" \
        --action-names "sts:GetCallerIdentity" \
        --region "$REGION" >/dev/null 2>&1; then
    SIMULATE=false
    warn "cannot use iam:SimulatePrincipalPolicy (needs iam:SimulatePrincipalPolicy, and SSO"
    warn "  sessions often cannot be simulated). Falling back to read-only probes."
    warn "  A read succeeding does NOT prove a write would — treat PASS below as weak evidence."
fi

# Every action Terraform needs, grouped by the service that would fail. Destructive actions are
# included on purpose: `terraform destroy` and any replace-in-place needs them, and discovering that
# after building the environment is too late.
check_actions() {
    local label="$1"; shift
    local actions=("$@")

    if $SIMULATE; then
        local result
        result="$(aws iam simulate-principal-policy \
            --policy-source-arn "$POLICY_SOURCE" \
            --action-names "${actions[@]}" \
            --output json 2>&1)"

        if ! echo "$result" | grep -q "EvaluationResults"; then
            warn "$label — simulation failed: $(echo "$result" | head -1)"
            return
        fi

        local denied
        denied="$(echo "$result" | python -c '
import json, sys
data = json.load(sys.stdin)
# implicitDeny means no policy allows it; explicitDeny means something forbids it. Both fail.
bad = [r["EvalActionName"] for r in data["EvaluationResults"] if r["EvalDecision"] != "allowed"]
print(" ".join(bad))
')"
        if [ -z "$denied" ]; then
            ok "$label (${#actions[@]} actions)"
        else
            bad "$label — denied: $denied"
        fi
    else
        # Fallback: one cheap read per service.
        if eval "${FALLBACK_CMD:-false}" >/dev/null 2>&1; then
            ok "$label (read-only probe only)"
        else
            bad "$label — read probe failed"
        fi
    fi
}

FALLBACK_CMD="aws ec2 describe-vpcs --max-results 5 --region $REGION" \
check_actions "EC2 / VPC" \
    ec2:CreateVpc ec2:DeleteVpc ec2:CreateSubnet ec2:DeleteSubnet \
    ec2:CreateInternetGateway ec2:AttachInternetGateway ec2:CreateRouteTable ec2:CreateRoute \
    ec2:CreateSecurityGroup ec2:DeleteSecurityGroup \
    ec2:AuthorizeSecurityGroupIngress ec2:AuthorizeSecurityGroupEgress \
    ec2:CreateTags ec2:DescribeAvailabilityZones ec2:AllocateAddress ec2:CreateNatGateway

FALLBACK_CMD="aws ecs list-clusters --region $REGION" \
check_actions "ECS" \
    ecs:CreateCluster ecs:DeleteCluster ecs:RegisterTaskDefinition ecs:DeregisterTaskDefinition \
    ecs:CreateService ecs:UpdateService ecs:DeleteService ecs:DescribeServices ecs:DescribeTasks \
    ecs:TagResource ecs:PutClusterCapacityProviders ecs:ExecuteCommand

FALLBACK_CMD="aws ecr describe-repositories --max-results 5 --region $REGION" \
check_actions "ECR" \
    ecr:CreateRepository ecr:DeleteRepository ecr:PutLifecyclePolicy \
    ecr:PutImageScanningConfiguration ecr:SetRepositoryPolicy ecr:TagResource \
    ecr:GetAuthorizationToken ecr:InitiateLayerUpload ecr:UploadLayerPart ecr:PutImage \
    ecr:BatchCheckLayerAvailability ecr:CompleteLayerUpload

FALLBACK_CMD="aws rds describe-db-instances --max-records 20 --region $REGION" \
check_actions "RDS" \
    rds:CreateDBInstance rds:DeleteDBInstance rds:ModifyDBInstance rds:DescribeDBInstances \
    rds:CreateDBSubnetGroup rds:CreateDBParameterGroup rds:AddTagsToResource

FALLBACK_CMD="aws efs describe-file-systems --max-items 5 --region $REGION" \
check_actions "EFS" \
    elasticfilesystem:CreateFileSystem elasticfilesystem:DeleteFileSystem \
    elasticfilesystem:CreateMountTarget elasticfilesystem:CreateAccessPoint \
    elasticfilesystem:PutLifecycleConfiguration elasticfilesystem:TagResource

FALLBACK_CMD="aws elbv2 describe-load-balancers --page-size 5 --region $REGION" \
check_actions "ELB (ALB)" \
    elasticloadbalancing:CreateLoadBalancer elasticloadbalancing:DeleteLoadBalancer \
    elasticloadbalancing:CreateTargetGroup elasticloadbalancing:CreateListener \
    elasticloadbalancing:CreateRule elasticloadbalancing:ModifyLoadBalancerAttributes \
    elasticloadbalancing:AddTags

# IAM is the one most often withheld, and Terraform cannot create the task roles without it.
FALLBACK_CMD="aws iam list-roles --max-items 5" \
check_actions "IAM" \
    iam:CreateRole iam:DeleteRole iam:PutRolePolicy iam:DeleteRolePolicy \
    iam:AttachRolePolicy iam:DetachRolePolicy iam:PassRole iam:TagRole iam:GetRole

FALLBACK_CMD="aws kms list-keys --limit 5 --region $REGION" \
check_actions "KMS" \
    kms:CreateKey kms:CreateAlias kms:DeleteAlias kms:EnableKeyRotation \
    kms:PutKeyPolicy kms:TagResource kms:DescribeKey kms:ScheduleKeyDeletion

FALLBACK_CMD="aws secretsmanager list-secrets --max-results 5 --region $REGION" \
check_actions "Secrets Manager" \
    secretsmanager:CreateSecret secretsmanager:DeleteSecret secretsmanager:PutSecretValue \
    secretsmanager:GetSecretValue secretsmanager:TagResource secretsmanager:DescribeSecret

FALLBACK_CMD="aws logs describe-log-groups --limit 5 --region $REGION" \
check_actions "CloudWatch Logs" \
    logs:CreateLogGroup logs:DeleteLogGroup logs:PutRetentionPolicy logs:TagResource \
    logs:AssociateKmsKey logs:DescribeLogGroups

FALLBACK_CMD="aws s3api list-buckets" \
check_actions "S3" \
    s3:CreateBucket s3:DeleteBucket s3:PutBucketPolicy s3:PutBucketVersioning \
    s3:PutBucketPublicAccessBlock s3:PutEncryptionConfiguration s3:PutLifecycleConfiguration \
    s3:PutObject s3:DeleteObject s3:ListBucket

FALLBACK_CMD="aws cloudfront list-distributions --max-items 5" \
check_actions "CloudFront" \
    cloudfront:CreateDistribution cloudfront:UpdateDistribution cloudfront:DeleteDistribution \
    cloudfront:CreateOriginAccessControl cloudfront:CreateFunction cloudfront:PublishFunction \
    cloudfront:CreateResponseHeadersPolicy cloudfront:CreateInvalidation cloudfront:TagResource

FALLBACK_CMD="aws route53 list-hosted-zones --max-items 5" \
check_actions "Route 53" \
    route53:ListHostedZones route53:ChangeResourceRecordSets route53:GetHostedZone

# ---------------------------------------------------------------------------
# 2. Quotas and pre-existing state that would break an apply
# ---------------------------------------------------------------------------
head_ "Environment"

# Fargate needs the service-linked role. On a brand-new account it does not exist and the first
# CreateService fails with a message that does not name the cause.
if aws iam get-role --role-name AWSServiceRoleForECS >/dev/null 2>&1; then
    ok "AWSServiceRoleForECS exists"
else
    warn "AWSServiceRoleForECS missing — create it before apply:"
    warn "  aws iam create-service-linked-role --aws-service-name ecs.amazonaws.com"
fi

# VPC limit is 5 per region by default and this creates one.
VPC_COUNT="$(aws ec2 describe-vpcs --region "$REGION" --query 'length(Vpcs)' --output text 2>/dev/null || echo "?")"
if [ "$VPC_COUNT" != "?" ] && [ "$VPC_COUNT" -ge 5 ]; then
    bad "$VPC_COUNT VPCs already exist; the default limit is 5. Delete one or raise the quota."
else
    ok "VPCs in use: ${VPC_COUNT} (default limit 5)"
fi

# EIP limit matters only with NAT enabled, but check anyway — it is free to look.
EIP_COUNT="$(aws ec2 describe-addresses --region "$REGION" --query 'length(Addresses)' --output text 2>/dev/null || echo "?")"
ok "Elastic IPs in use: ${EIP_COUNT} (default limit 5; only needed if enable_nat_gateway=true)"

# A leftover secret in its recovery window blocks re-creating the same name. This is the single most
# common re-apply failure after a destroy, and the error does not suggest the fix.
SCHEDULED="$(aws secretsmanager list-secrets --region "$REGION" \
    --include-planned-deletion \
    --filters Key=name,Values=influencrm- \
    --query 'length(SecretList[?DeletedDate!=null])' --output text 2>/dev/null || echo 0)"
if [ "$SCHEDULED" != "0" ] && [ "$SCHEDULED" != "None" ] && [ "$SCHEDULED" != "?" ]; then
    bad "$SCHEDULED influencrm-* secret(s) pending deletion — CreateSecret will fail on the name."
    bad "  force-delete them: aws secretsmanager delete-secret --secret-id <name> --force-delete-without-recovery"
else
    ok "no influencrm-* secrets pending deletion"
fi

# Does the Route 53 zone the config looks up actually exist?
if aws route53 list-hosted-zones --query "HostedZones[?Name=='tejdux.com.'].Id" --output text 2>/dev/null | grep -q .; then
    ok "hosted zone tejdux.com found"
else
    warn "hosted zone tejdux.com NOT found in this account"
    warn "  fine while api_domain and static_site_certificate_arn are empty; the lookup is skipped"
fi

# ACM certificates must be in us-east-1 for CloudFront, whatever the deployment region is.
CERT_COUNT="$(aws acm list-certificates --region us-east-1 \
    --query "length(CertificateSummaryList[?contains(DomainName,'tejdux')])" --output text 2>/dev/null || echo "?")"
ok "ACM certificates in us-east-1 matching 'tejdux': ${CERT_COUNT}"
if [ "$CERT_COUNT" != "0" ] && [ "$CERT_COUNT" != "?" ]; then
    aws acm list-certificates --region us-east-1 \
        --query "CertificateSummaryList[?contains(DomainName,'tejdux')].[DomainName,CertificateArn]" \
        --output text 2>/dev/null | sed 's/^/        /'
    echo "        ^ a WILDCARD (*.tejdux.com) is needed for the seven micro-frontend subdomains."
fi

# Docker, for build-and-push.
if docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
    ok "docker daemon reachable (needed by build-and-push.sh)"
else
    bad "docker daemon not reachable — build-and-push.sh cannot run"
fi

# ---------------------------------------------------------------------------
head_ "Result"
printf "  %d passed, %d failed, %d warnings\n\n" "$PASS" "$FAIL" "$WARN"

if [ "$FAIL" -gt 0 ]; then
    echo "${RED}Not clear to apply.${OFF} Fix the FAIL lines above first — a partial apply is worse"
    echo "than a refused one, because half the environment ends up built and billing."
    exit 1
fi

if ! $SIMULATE; then
    echo "${YELLOW}Clear to apply, with a caveat:${OFF} permissions could not be simulated, so the"
    echo "checks above only prove reads work. A write may still be denied mid-apply."
    exit 0
fi

echo "${GREEN}Clear to apply.${OFF}"
echo
echo "  cd infrastructure/test/terraform"
echo "  terraform init"
echo "  terraform apply -var image_tag=v1.1.0"
exit 0
