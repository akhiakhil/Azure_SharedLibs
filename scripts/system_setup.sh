!/usr/bin/env bash

# This script assumes that a Key Vault with the name 'infra-kv-devtest' already exists. 

function createStorageAccount() {
    rg=k8s-rg
    sa=tfstoragedevtest
    container=tfstate
    subscription=DevTest
    kvName=infra-kv-devtest

    # Set current subscription
    az account set -s $subscription

    rgExists="$(az group exists -n $rg)"

    if [ "$rgExists" = "true" ]; then
        echo "Resource Group $rg exists"
    else
        echo "Creating Resource Group $rg"

        # Resource group
        az group create -n $rg -l uksouth
    fi

    storageExists="$(az storage account list --query "[?name=='$sa'] | length(@)")"

    if [ "$storageExists" -ne "0" ]; then
        echo "Storage account $sa exists"
    else
        echo "Creating Storage Account $sa"

        # Storage Account
        az storage account create -g $rg -n $sa --sku Standard_LRS --encryption-services blob

        echo "Creating Container $container"

        # Storage Container
        az storage container create --name $container --account-name $sa

        # Create a secret in Key Vault to store the storage account key required by Terraform
        storageAccountKey="$(az storage account keys list --account-name $sa --query [0].value -o tsv)"

        az keyvault secret set -n arm-access-key --vault-name $kvName --value $storageAccountKey
    fi
}

createStorageAccount