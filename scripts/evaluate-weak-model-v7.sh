#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${PROJECT_DIR}"
rm -f \
  "${PROJECT_DIR}/target/weak-model-compiler-v7-report.json" \
  "${PROJECT_DIR}/target/weak-model-compiler-v7-readonly-shadow.json" \
  "${PROJECT_DIR}/target/weak-model-compiler-v7-qualification.json"
./mvnw -DskipTests=false \
  -Dtest=DesignerAcceptanceV7GoldenCorpusTest,DesignerAcceptanceShadowEvaluatorTest,DesignerAcceptanceReadOnlyShadowTest,DesignerAcceptanceV7MeasurementRegistryTest,DesignerAcceptanceCapabilitySolverTest \
  test

REPORT="${PROJECT_DIR}/target/weak-model-compiler-v7-report.json"
test -s "${REPORT}"
echo "Synthetic expectations and exact-guard report (not authoritative): ${REPORT}"
shasum -a 256 "${REPORT}"

SHADOW_REPORT="${PROJECT_DIR}/target/weak-model-compiler-v7-readonly-shadow.json"
test -s "${SHADOW_REPORT}"
echo "Authoritative read-only same-input measurement: ${SHADOW_REPORT}"
shasum -a 256 "${SHADOW_REPORT}"

QUALIFICATION_REPORT="${PROJECT_DIR}/target/weak-model-compiler-v7-qualification.json"
test -s "${QUALIFICATION_REPORT}"
echo "Complete local qualification gate: ${QUALIFICATION_REPORT}"
shasum -a 256 "${QUALIFICATION_REPORT}"
