!/usr/bin/env bash

function deleteStorageAccount() {
    rg=k8s-rg
    sa=tfstoragedevtest
    subscription=DevTest

    # Set current subscription
    az account set -s $subscription

    rgExists="$(az group exists -n $rg)"

    if [ "$rgExists" = "true" ]; then
        storageExists="$(az storage account list --query "[?name=='$sa'] | length(@)")"

        if [ "$storageExists" -ne "0" ]; then
            echo "Deleting Storage Account $sa"
            az storage account delete -n $sa -g $rg -y
        fi

        # Delete resource group 
        echo "Deleting Resource Group $rg"
        az group delete -n $rg -y
    fi
}

deleteStorageAccount