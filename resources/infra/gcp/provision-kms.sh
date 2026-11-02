#!/usr/bin/env bash
set -euo pipefail
# Explicit project only: never silently use the operator's default project.
: "${WALLET_GCP_PROJECT:?set the target GCP project ID}"
WALLET_KMS_LOCATION=${WALLET_KMS_LOCATION:-us-central1}
WALLET_KMS_KEYRING=${WALLET_KMS_KEYRING:-surprising-wallet-mpc}
gcloud services enable cloudkms.googleapis.com iam.googleapis.com --project="$WALLET_GCP_PROJECT" --quiet
if ! gcloud kms keyrings describe "$WALLET_KMS_KEYRING" --location="$WALLET_KMS_LOCATION" --project="$WALLET_GCP_PROJECT" --quiet >/dev/null 2>&1; then
  gcloud kms keyrings create "$WALLET_KMS_KEYRING" --location="$WALLET_KMS_LOCATION" --project="$WALLET_GCP_PROJECT" --quiet
fi
for holder in 1 2 3; do
  key="holder-${holder}-storage"
  account="wallet-mpc-holder-${holder}"
  email="${account}@${WALLET_GCP_PROJECT}.iam.gserviceaccount.com"
  if ! gcloud kms keys describe "$key" --keyring="$WALLET_KMS_KEYRING" --location="$WALLET_KMS_LOCATION" --project="$WALLET_GCP_PROJECT" --quiet >/dev/null 2>&1; then
    gcloud kms keys create "$key" --keyring="$WALLET_KMS_KEYRING" --location="$WALLET_KMS_LOCATION" --project="$WALLET_GCP_PROJECT" --purpose=encryption --protection-level=software --quiet
  fi
  if ! gcloud iam service-accounts describe "$email" --project="$WALLET_GCP_PROJECT" --quiet >/dev/null 2>&1; then
    gcloud iam service-accounts create "$account" --display-name="Wallet MPC holder ${holder}" --project="$WALLET_GCP_PROJECT" --quiet
  fi
  # A newly created IAM identity can take a few seconds to reach Cloud KMS.
  for attempt in 1 2 3 4 5; do
    if gcloud kms keys add-iam-policy-binding "$key" --keyring="$WALLET_KMS_KEYRING" --location="$WALLET_KMS_LOCATION" --project="$WALLET_GCP_PROJECT" --member="serviceAccount:${email}" --role=roles/cloudkms.cryptoKeyDecrypter --quiet >/dev/null; then
      break
    fi
    if [[ "$attempt" = 5 ]]; then exit 1; fi
    sleep 3
  done
  printf 'Ready: %s -> projects/%s/locations/%s/keyRings/%s/cryptoKeys/%s (decrypt only)\n' "$email" "$WALLET_GCP_PROJECT" "$WALLET_KMS_LOCATION" "$WALLET_KMS_KEYRING" "$key"
done
