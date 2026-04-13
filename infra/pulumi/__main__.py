"""Pulumi program for backend infrastructure.

This stack provisions AWS resources required by the SpeechPilot backend runtime:
- ECR repository for backend container images
- GitHub Actions OIDC IAM role for ECR pushes
- Security group for the realtime cache
- ElastiCache subnet group
- ElastiCache Redis replication group for websocket realtime state

PostgreSQL is intentionally not provisioned here. The backend reuses an existing
database instance and expects its connection URL through deployment-time secrets.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass

import pulumi
import pulumi_aws as aws


@dataclass(frozen=True)
class RuntimeContext:
    env: str
    account_id: str
    region: str
    github_repository: str


@dataclass(frozen=True)
class CacheNetworkConfig:
    vpc_id: str
    subnet_ids: list[str]
    allowed_security_group_ids: list[str]
    allowed_cidr_blocks: list[str]


def _env_from_stack(stack_name: str) -> str:
    prefix = "speechpilot-backend-"
    if stack_name.startswith(prefix):
        return stack_name[len(prefix) :]
    return stack_name


def _csv_values(raw: str | None) -> list[str]:
    if not raw:
        return []
    return [item.strip() for item in raw.split(",") if item.strip()]


def _load_context(cfg: pulumi.Config) -> RuntimeContext:
    account_id = aws.get_caller_identity().account_id
    region = aws.config.region or os.getenv("AWS_REGION") or "ap-southeast-2"
    repository = os.getenv("GITHUB_REPOSITORY") or cfg.get("github_repository") or "unknown/repository"
    env = cfg.get("deploy_env") or _env_from_stack(pulumi.get_stack())
    return RuntimeContext(
        env=env,
        account_id=account_id,
        region=region,
        github_repository=repository,
    )


def _load_cache_network(cfg: pulumi.Config) -> CacheNetworkConfig:
    vpc_id = os.getenv("VPC_ID") or cfg.get("vpc_id")
    subnet_ids = _csv_values(os.getenv("CACHE_SUBNET_IDS") or cfg.get("cache_subnet_ids"))
    allowed_security_group_ids = _csv_values(
        os.getenv("CACHE_ALLOWED_SECURITY_GROUP_IDS")
        or os.getenv("EKS_CLUSTER_SECURITY_GROUP_ID")
        or cfg.get("cache_allowed_security_group_ids")
        or cfg.get("eks_cluster_security_group_id")
    )
    allowed_cidr_blocks = _csv_values(
        os.getenv("CACHE_ALLOWED_CIDR_BLOCKS") or cfg.get("cache_allowed_cidr_blocks")
    )

    if not vpc_id:
        raise ValueError("Missing VPC_ID / vpc_id for ElastiCache provisioning.")
    if not subnet_ids:
        raise ValueError("Missing CACHE_SUBNET_IDS / cache_subnet_ids for ElastiCache provisioning.")
    if not allowed_security_group_ids and not allowed_cidr_blocks:
        raise ValueError(
            "Provide CACHE_ALLOWED_SECURITY_GROUP_IDS, EKS_CLUSTER_SECURITY_GROUP_ID, or CACHE_ALLOWED_CIDR_BLOCKS so workloads can reach ElastiCache."
        )

    return CacheNetworkConfig(
        vpc_id=vpc_id,
        subnet_ids=subnet_ids,
        allowed_security_group_ids=allowed_security_group_ids,
        allowed_cidr_blocks=allowed_cidr_blocks,
    )


def _tags(ctx: RuntimeContext) -> dict[str, str]:
    return {
        "project": "speechpilot",
        "component": "backend",
        "environment": ctx.env,
        "managed-by": "pulumi",
    }


def _create_ecr_repo(cfg: pulumi.Config, ctx: RuntimeContext) -> aws.ecr.Repository:
    repository_name = cfg.get("ecr_repository_name") or "speechpilot-backend"
    return aws.ecr.Repository(
        "backendEcrRepository",
        name=repository_name,
        image_scanning_configuration=aws.ecr.RepositoryImageScanningConfigurationArgs(
            scan_on_push=True
        ),
        image_tag_mutability="MUTABLE",
        tags=_tags(ctx),
    )


def _create_github_ecr_push_role(
    ctx: RuntimeContext, ecr_repo: aws.ecr.Repository
) -> aws.iam.Role:
    assume_doc = aws.iam.get_policy_document_output(
        statements=[
            aws.iam.GetPolicyDocumentStatementArgs(
                effect="Allow",
                actions=["sts:AssumeRoleWithWebIdentity"],
                principals=[
                    aws.iam.GetPolicyDocumentStatementPrincipalArgs(
                        type="Federated",
                        identifiers=[
                            f"arn:aws:iam::{ctx.account_id}:oidc-provider/token.actions.githubusercontent.com"
                        ],
                    )
                ],
                conditions=[
                    aws.iam.GetPolicyDocumentStatementConditionArgs(
                        test="StringEquals",
                        variable="token.actions.githubusercontent.com:aud",
                        values=["sts.amazonaws.com"],
                    ),
                    aws.iam.GetPolicyDocumentStatementConditionArgs(
                        test="StringLike",
                        variable="token.actions.githubusercontent.com:sub",
                        values=[f"repo:{ctx.github_repository}:*"],
                    ),
                ],
            )
        ]
    )

    role = aws.iam.Role(
        "githubActionsEcrPushRole",
        name=f"speechpilot-backend-ecr-{ctx.env}",
        assume_role_policy=assume_doc.json,
        tags=_tags(ctx),
    )

    policy_doc = aws.iam.get_policy_document_output(
        statements=[
            aws.iam.GetPolicyDocumentStatementArgs(
                effect="Allow",
                actions=["ecr:GetAuthorizationToken"],
                resources=["*"],
            ),
            aws.iam.GetPolicyDocumentStatementArgs(
                effect="Allow",
                actions=[
                    "ecr:BatchCheckLayerAvailability",
                    "ecr:CompleteLayerUpload",
                    "ecr:InitiateLayerUpload",
                    "ecr:PutImage",
                    "ecr:UploadLayerPart",
                    "ecr:BatchGetImage",
                    "ecr:DescribeImages",
                ],
                resources=[ecr_repo.arn],
            ),
        ]
    )

    aws.iam.RolePolicy(
        "githubActionsEcrPushPolicy",
        role=role.id,
        policy=policy_doc.json,
    )

    return role


def _create_cache_security_group(
    ctx: RuntimeContext, network: CacheNetworkConfig, port: int
) -> aws.ec2.SecurityGroup:
    ingress_rules = []
    for allowed_sg in network.allowed_security_group_ids:
        ingress_rules.append(
            aws.ec2.SecurityGroupIngressArgs(
                protocol="tcp",
                from_port=port,
                to_port=port,
                security_groups=[allowed_sg],
                description=f"Allow Redis traffic from {allowed_sg}",
            )
        )
    if network.allowed_cidr_blocks:
        ingress_rules.append(
            aws.ec2.SecurityGroupIngressArgs(
                protocol="tcp",
                from_port=port,
                to_port=port,
                cidr_blocks=network.allowed_cidr_blocks,
                description="Allow Redis traffic from configured CIDR blocks",
            )
        )

    return aws.ec2.SecurityGroup(
        "realtimeCacheSecurityGroup",
        name=f"speechpilot-backend-cache-{ctx.env}",
        description=f"SpeechPilot backend cache access for {ctx.env}",
        vpc_id=network.vpc_id,
        ingress=ingress_rules,
        egress=[
            aws.ec2.SecurityGroupEgressArgs(
                protocol="-1",
                from_port=0,
                to_port=0,
                cidr_blocks=["0.0.0.0/0"],
                description="Allow all outbound traffic",
            )
        ],
        tags=_tags(ctx),
    )


def _create_elasticache(
    cfg: pulumi.Config,
    ctx: RuntimeContext,
    network: CacheNetworkConfig,
) -> tuple[aws.elasticache.SubnetGroup, aws.ec2.SecurityGroup, aws.elasticache.ReplicationGroup]:
    node_type = cfg.get("cache_node_type") or os.getenv("CACHE_NODE_TYPE") or "cache.t4g.small"
    num_cache_clusters = int(
        cfg.get("cache_num_cache_clusters") or os.getenv("CACHE_NUM_CACHE_CLUSTERS") or "1"
    )
    engine_version = cfg.get("cache_engine_version") or os.getenv("CACHE_ENGINE_VERSION") or "7.1"
    port = int(cfg.get("cache_port") or os.getenv("CACHE_PORT") or "6379")
    parameter_group_name = (
        cfg.get("cache_parameter_group_name")
        or os.getenv("CACHE_PARAMETER_GROUP_NAME")
        or "default.redis7"
    )

    subnet_group = aws.elasticache.SubnetGroup(
        "realtimeCacheSubnetGroup",
        name=f"speechpilot-backend-cache-{ctx.env}",
        subnet_ids=network.subnet_ids,
        description=f"SpeechPilot backend cache subnet group for {ctx.env}",
        tags=_tags(ctx),
    )

    security_group = _create_cache_security_group(ctx, network, port)

    replication_group = aws.elasticache.ReplicationGroup(
        "realtimeCache",
        replication_group_id=f"speechpilot-backend-{ctx.env}",
        description=f"SpeechPilot backend realtime cache for {ctx.env}",
        engine="redis",
        engine_version=engine_version,
        node_type=node_type,
        num_cache_clusters=num_cache_clusters,
        port=port,
        subnet_group_name=subnet_group.name,
        security_group_ids=[security_group.id],
        parameter_group_name=parameter_group_name,
        at_rest_encryption_enabled=True,
        automatic_failover_enabled=num_cache_clusters > 1,
        multi_az_enabled=num_cache_clusters > 1,
        apply_immediately=True,
        tags=_tags(ctx),
    )

    return subnet_group, security_group, replication_group


def main() -> None:
    cfg = pulumi.Config()
    ctx = _load_context(cfg)
    cache_network = _load_cache_network(cfg)

    ecr_repository = _create_ecr_repo(cfg, ctx)
    github_ecr_role = _create_github_ecr_push_role(ctx, ecr_repository)
    cache_subnet_group, cache_security_group, cache_replication_group = _create_elasticache(
        cfg, ctx, cache_network
    )

    pulumi.export("deploy_env", ctx.env)
    pulumi.export("aws_region", ctx.region)
    pulumi.export("postgres_managed_by_pulumi", False)

    pulumi.export("ecr_repository_url", ecr_repository.repository_url)
    pulumi.export("ecr_repository_name", ecr_repository.name)
    pulumi.export("github_actions_ecr_role_arn", github_ecr_role.arn)

    pulumi.export("elasticache_subnet_group_name", cache_subnet_group.name)
    pulumi.export("elasticache_security_group_id", cache_security_group.id)
    pulumi.export("elasticache_primary_endpoint_address", cache_replication_group.primary_endpoint_address)
    pulumi.export("elasticache_reader_endpoint_address", cache_replication_group.reader_endpoint_address)
    pulumi.export("elasticache_port", cache_replication_group.port)
    pulumi.export(
        "elasticache_redis_url",
        pulumi.Output.all(
            cache_replication_group.primary_endpoint_address,
            cache_replication_group.port,
        ).apply(
            lambda values: (
                f"redis://{values[0]}:{values[1]}/0"
                if values[0] not in (None, "") and values[1] not in (None, "")
                else ""
            )
        ),
    )


main()