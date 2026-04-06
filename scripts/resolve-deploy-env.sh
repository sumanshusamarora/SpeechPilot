#!/usr/bin/env bash
set -euo pipefail

# Resolve deployment environment and write GitHub Actions outputs.
# Usage:
#   bash scripts/resolve-deploy-env.sh "$GITHUB_OUTPUT"

OUTPUT_PATH="${1:-${GITHUB_OUTPUT:-}}"
if [[ -z "${OUTPUT_PATH}" ]]; then
  echo "Missing output path. Pass as first arg or set GITHUB_OUTPUT." >&2
  exit 1
fi

REF="${GITHUB_REF:-}"
SHA="${GITHUB_SHA:-}"
if [[ -z "${SHA}" ]]; then
  SHA="$(git rev-parse HEAD 2>/dev/null || true)"
fi
SHORT_SHA="$(printf '%s' "${SHA}" | cut -c1-7)"

DEPLOY_ENV='none'
GITHUB_ENVIRONMENT='none'
ENVIRONMENT='none'

OVERRIDE="${DEPLOY_ENV_OVERRIDE:-}"
if [[ "${OVERRIDE}" == 'auto' ]]; then
  OVERRIDE=''
fi

if [[ -n "${OVERRIDE}" ]]; then
  case "${OVERRIDE}" in
    development)
      if [[ "${REF}" != 'refs/heads/main' ]]; then
        echo "Development deployment requires refs/heads/main (current ref: ${REF})." >&2
        exit 1
      fi
      DEPLOY_ENV='development'
      GITHUB_ENVIRONMENT='development'
      ENVIRONMENT='development'
      ;;
    staging)
      if [[ ! "${REF}" =~ ^refs/tags/staging- ]]; then
        echo "Staging deployment requires refs/tags/staging-* (current ref: ${REF})." >&2
        exit 1
      fi
      DEPLOY_ENV='staging'
      GITHUB_ENVIRONMENT='staging'
      ENVIRONMENT='staging'
      ;;
    production)
      if [[ ! "${REF}" =~ ^refs/tags/production- ]]; then
        echo "Production deployment requires refs/tags/production-* (current ref: ${REF})." >&2
        exit 1
      fi
      DEPLOY_ENV='production'
      GITHUB_ENVIRONMENT='production'
      ENVIRONMENT='production'
      ;;
    *)
      echo "Invalid DEPLOY_ENV_OVERRIDE=${OVERRIDE}. Expected development|staging|production." >&2
      exit 1
      ;;
  esac
else
  case "${REF}" in
    refs/heads/main)
      DEPLOY_ENV='development'
      GITHUB_ENVIRONMENT='development'
      ENVIRONMENT='development'
      ;;
    refs/tags/staging-*)
      DEPLOY_ENV='staging'
      GITHUB_ENVIRONMENT='staging'
      ENVIRONMENT='staging'
      ;;
    refs/tags/production-*)
      DEPLOY_ENV='production'
      GITHUB_ENVIRONMENT='production'
      ENVIRONMENT='production'
      ;;
  esac
fi

{
  echo "environment=${ENVIRONMENT}"
  echo "deploy_env=${DEPLOY_ENV}"
  echo "github_environment=${GITHUB_ENVIRONMENT}"
  echo "image_tag=${SHORT_SHA}"
  echo "short_sha=${SHORT_SHA}"
} >> "${OUTPUT_PATH}"