# Deployment

1. Wait for the build on master to complete successfully.
2. Run the command:

    ```
    ./az-deploy.sh
    ```
3. You will be asked for your **gitlab** user credentials.
4. You will be asked to complete a Microsoft Azure challenge-response.
5. Respond to any y/n questions correctly.
6. Wait. It takes about 30-60 seconds to fully deploy the container.
7. If you want to manually check the status of the deployment, run the command: <br>

    ```
    az container show --resource-group cordite-network-map --name cordite-network-map --query "{FQDN:ipAddress.fqdn,ProvisioningState:provisioningState}" --out table
    ```
8. When it's ready, the network map is hosted here [http://cordite-network-map.westeurope.azurecontainer.io/](http://cordite-network-map.westeurope.azurecontainer.io/).

# Command line parameters

| Property   | Env Variable   | Default             | Description           |
| ---------- | -------------- | ------------------- | --------------------- |
| port       | NMS_PORT       | 8080                | web port              |
| notary.dir | NMS_NOTARY_DIR | notary-certificates | notary cert directory |
| db.dir | NMS_DB_DIR | .db | directory for storing state. at present only the whitelist.txt file |

