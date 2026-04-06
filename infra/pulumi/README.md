# SpeechPilot Backend Infrastructure

This Pulumi project provisions the AWS resources required by the deployed SpeechPilot backend.

Provisioned resources:

- ECR repository for backend container images
- GitHub Actions OIDC IAM role for ECR pushes
- VPC security group for the realtime store
- ElastiCache subnet group
- ElastiCache Redis replication group for websocket realtime state

Not provisioned here:

- PostgreSQL / RDS
- EKS cluster
- load balancers / ingress

The backend reuses an existing PostgreSQL instance and an existing EKS cluster. Configure those through GitHub environment variables and secrets.

## Stack Names

Use stack names that match the workflow contract:

- speechpilot-backend-development
- speechpilot-backend-staging
- speechpilot-backend-production

## Environment Variables Consumed During Pulumi Runs

Required:

- AWS_REGION
- PULUMI_BACKEND_URL
- GITHUB_REPOSITORY

One of these network sources is required:

- EKS_CLUSTER_NAME
- or VPC_ID plus CACHE_SUBNET_IDS plus one of CACHE_ALLOWED_SECURITY_GROUP_IDS or CACHE_ALLOWED_CIDR_BLOCKS

Optional:

- CACHE_NODE_TYPE
- CACHE_NUM_CACHE_CLUSTERS
- CACHE_ENGINE_VERSION
- CACHE_PORT
- CACHE_PARAMETER_GROUP_NAME
- CACHE_ALLOWED_SECURITY_GROUP_IDS
- CACHE_ALLOWED_CIDR_BLOCKS

## Pulumi Outputs

Capture these outputs into GitHub environment variables after a successful run:

- ecr_repository_url -> ECR_REPOSITORY
- github_actions_ecr_role_arn -> ECR_PUSH_ROLE_ARN
- elasticache_redis_url -> SPEECHPILOT_REDIS_URL or SPEECHPILOT_ELASTICACHE_URL
- elasticache_primary_endpoint_address -> optional observability / troubleshooting variable
- elasticache_security_group_id -> optional network troubleshooting variable

## Local Usage

```bash
cd infra/pulumi
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

export AWS_REGION=ap-southeast-2
export PULUMI_BACKEND_URL=s3://your-pulumi-state-bucket
export GITHUB_REPOSITORY=your-org/SpeechPilot
export EKS_CLUSTER_NAME=your-eks-cluster
export PULUMI_CONFIG_PASSPHRASE=

pulumi login "${PULUMI_BACKEND_URL}"
pulumi stack select speechpilot-backend-development || pulumi stack init speechpilot-backend-development
pulumi preview
```