# Backend Workflow Environment Contract

This document lists the GitHub Actions variables and secrets used by the backend workflows and the Pulumi infrastructure pipeline.

Backend workflow files:

- backend-ci.yml
- backend-build.yml
- backend-quality.yml
- backend-cd.yml
- backend-infra-pulumi.yml

Environment-specific deployment values should normally be stored on the matching GitHub Environment (`development`, `staging`, `production`). Repo-level values are appropriate for shared defaults such as `AWS_REGION` and `PULUMI_BACKEND_URL`.

## Variables

| Name | Kind | Level | Required | Used By | Notes |
| --- | --- | --- | --- | --- | --- |
| `AWS_REGION` | Variable | Repo or Environment | Yes | `backend-cd.yml`, `backend-infra-pulumi.yml` | Defaults to `ap-southeast-2` if omitted, but set it explicitly for clarity. |
| `PULUMI_BACKEND_URL` | Variable | Repo | Yes | `backend-infra-pulumi.yml` | Pulumi state backend, typically an S3 URL such as `s3://your-pulumi-state-bucket`. |
| `PROJECT_PROVISIONER_ROLE_ARN` | Variable | Repo | Yes unless `PROVISIONER_ROLE_ARN` is set | `backend-infra-pulumi.yml` | Base GitHub OIDC role used to bootstrap the dedicated Pulumi provisioner role. |
| `PROVISIONER_ROLE_ARN` | Variable | Repo | Fallback | `backend-infra-pulumi.yml` | Fallback for `PROJECT_PROVISIONER_ROLE_ARN`. |
| `EKS_CLUSTER_NAME` | Variable | Environment | Yes for CD, recommended for Pulumi | `backend-cd.yml`, `backend-infra-pulumi.yml` | Lets both workflows resolve cluster network settings and kubeconfig automatically. |
| `CLUSTER_DEPLOY_ROLE_ARN` | Variable | Environment | Yes unless `CLUSTER_ACCESS_ROLE_ARN` is set | `backend-cd.yml` | GitHub OIDC role used to talk to the target EKS cluster. |
| `CLUSTER_ACCESS_ROLE_ARN` | Variable | Environment | Fallback | `backend-cd.yml` | Fallback for `CLUSTER_DEPLOY_ROLE_ARN`. |
| `ECR_REPOSITORY` | Variable | Environment | Yes | `backend-cd.yml` | Copy from Pulumi output `ecr_repository_url`. |
| `ECR_PUSH_ROLE_ARN` | Variable | Environment | Yes | `backend-cd.yml` | Copy from Pulumi output `github_actions_ecr_role_arn`. |
| `VPC_ID` | Variable | Environment | Optional if `EKS_CLUSTER_NAME` is set | `backend-infra-pulumi.yml` | Manual VPC override for ElastiCache provisioning. |
| `CACHE_SUBNET_IDS` | Variable | Environment | Optional if `EKS_CLUSTER_NAME` is set | `backend-infra-pulumi.yml` | Comma-separated subnet ids for ElastiCache. Prefer private subnets. |
| `CACHE_ALLOWED_SECURITY_GROUP_IDS` | Variable | Environment | Optional if `EKS_CLUSTER_NAME` is set | `backend-infra-pulumi.yml` | Comma-separated security group ids allowed to connect to ElastiCache. Auto-resolves from EKS cluster security group when omitted and `EKS_CLUSTER_NAME` is present. |
| `CACHE_ALLOWED_CIDR_BLOCKS` | Variable | Environment | Optional | `backend-infra-pulumi.yml` | CIDR-based alternative to `CACHE_ALLOWED_SECURITY_GROUP_IDS`. |
| `CACHE_NODE_TYPE` | Variable | Environment | No | `backend-infra-pulumi.yml` | Defaults to `cache.t4g.small`. |
| `CACHE_NUM_CACHE_CLUSTERS` | Variable | Environment | No | `backend-infra-pulumi.yml` | Defaults to `1`. Increase for HA. |
| `CACHE_ENGINE_VERSION` | Variable | Environment | No | `backend-infra-pulumi.yml` | Defaults to `7.1`. |
| `SPEECHPILOT_REALTIME_STORE_BACKEND` | Variable | Environment | No | `backend-cd.yml` | Defaults to `elasticache`. Set to `redis` only when you intentionally deploy against a non-ElastiCache Redis endpoint. |
| `SPEECHPILOT_LOG_LEVEL` | Variable | Environment | No | `backend-cd.yml` | Defaults to `INFO`. |
| `SPEECHPILOT_REPLAY_ENABLED` | Variable | Environment | No | `backend-cd.yml` | Defaults to `true`. |
| `SPEECHPILOT_STT_MODEL_SIZE` | Variable | Environment | No | `backend-cd.yml` | Defaults to `small.en`. |
| `SPEECHPILOT_STT_LANGUAGE` | Variable | Environment | No | `backend-cd.yml` | Defaults to `en`. |
| `SPEECHPILOT_STT_DEVICE` | Variable | Environment | No | `backend-cd.yml` | Defaults to `cpu`. |
| `SPEECHPILOT_STT_COMPUTE_TYPE` | Variable | Environment | No | `backend-cd.yml` | Defaults to `int8`. |

## Secrets

| Name | Kind | Level | Required | Used By | Notes |
| --- | --- | --- | --- | --- | --- |
| `DATABASE_URL` | Secret | Environment | Yes | `backend-cd.yml` | Full backend database URL. The CD workflow maps it to `SPEECHPILOT_POSTGRES_URL` inside the Kubernetes secret. |
| `SPEECHPILOT_ELASTICACHE_URL` | Secret or Variable | Environment | Recommended | `backend-cd.yml` | Preferred managed-cache endpoint. Copy from Pulumi output `elasticache_redis_url`. |
| `SPEECHPILOT_REDIS_URL` | Secret or Variable | Environment | Optional fallback | `backend-cd.yml` | Alternative Redis-compatible endpoint. The CD workflow accepts either this or `SPEECHPILOT_ELASTICACHE_URL`. |

## Pulumi Output Mapping

| Pulumi Output | GitHub Variable / Secret | Notes |
| --- | --- | --- |
| `ecr_repository_url` | `ECR_REPOSITORY` | Environment variable |
| `github_actions_ecr_role_arn` | `ECR_PUSH_ROLE_ARN` | Environment variable |
| `elasticache_redis_url` | `SPEECHPILOT_ELASTICACHE_URL` or `SPEECHPILOT_REDIS_URL` | Environment secret or variable |

## Deployment Pattern

- `main` branch deploys the backend to the `development` GitHub Environment.
- `staging-*` tags deploy the backend to the `staging` GitHub Environment.
- `production-*` tags deploy the backend to the `production` GitHub Environment.
- The Pulumi workflow is manual by design and should be run before the first deployment to a given environment or when infrastructure changes are needed.
