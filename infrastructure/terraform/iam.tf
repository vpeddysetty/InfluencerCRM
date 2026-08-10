# The ECS execution and task roles used to live here. Both are GONE with ECS itself, and the reason is
# that neither can be assumed by anything any more: both trusted `ecs-tasks.amazonaws.com`, and there are
# no ECS tasks.
#
# WHAT REPLACED THEM, and what that costs. The single instance role in compose-ec2.tf
# (aws_iam_role.compose_instance) now does the work of both:
#
#   The EXECUTION role pulled images and resolved secrets before any container started. The boot script
#   does that now, so the instance role carries ECR pull, secretsmanager:GetSecretValue and kms:Decrypt.
#
#   The TASK role was what the APPLICATION could do to AWS - EFS mount, SES send. The instance role
#   carries those too, because the containers inherit the instance's credentials through IMDS.
#
# THE SEPARATION IS GENUINELY LOST, and it is the main security cost of leaving ECS. Under ECS the
# application could not read a secret ARN; it only received the values ECS injected. Now a process that
# reaches IMDS from inside a container can read every secret this platform owns. Two things bound it:
# http_put_response_hop_limit = 2 on the launch template, and the fact that the secrets are already in
# the container's environment anyway - so this widens what an attacker reaches, it does not hand them
# something they had no path to.
#
# Restoring the split means either an ECS-shaped control plane again, or per-container credentials from
# something like a sidecar that vends scoped tokens. Neither is worth it at one instance; both become
# worth it at more than one, alongside the Redis session store and the ALB.
#
# This file is intentionally left as a comment rather than deleted, because "where did the task role go"
# is the first question anyone reading compose-ec2.tf will ask.
