/*
 * Copyright 2021, Microsoft Corp
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nextflow.cloud.azure.batch

import java.nio.file.Path
import java.time.OffsetDateTime

import com.azure.storage.blob.BlobContainerClient
import com.azure.storage.blob.BlobServiceClient
import com.azure.storage.blob.BlobServiceClientBuilder
import com.azure.storage.blob.models.UserDelegationKey
import com.azure.storage.blob.sas.BlobContainerSasPermission
import com.azure.storage.blob.sas.BlobSasPermission
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues
import com.azure.storage.common.StorageSharedKeyCredential
import com.azure.storage.common.sas.AccountSasPermission
import com.azure.storage.common.sas.AccountSasResourceType
import com.azure.storage.common.sas.AccountSasService
import com.azure.storage.common.sas.AccountSasSignatureValues
import com.azure.storage.common.policy.RequestRetryOptions
import com.azure.storage.common.policy.RetryPolicyType
import com.azure.storage.common.policy.RequestRetryPolicy
import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.util.logging.Slf4j
import nextflow.cloud.azure.nio.AzPath
import nextflow.util.Duration
/**
 * Azure helper functions
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class AzHelper {

    static private AzPath az0(Path path){
        if( path !instanceof AzPath )
            throw new IllegalArgumentException("Not a valid Azure path: $path [${path?.getClass()?.getName()}]")
        return (AzPath)path
    }

    static String toHttpUrl(Path path, String sas=null) {
        def url = az0(path).blobClient().getBlobUrl()
        url = URLDecoder.decode(url, 'UTF-8').stripEnd('/')
        return !sas ? url : "${url}?${sas}"
    }

    static String toContainerUrl(Path path, String sas) {
        def url = az0(path).containerClient().getBlobContainerUrl()
        url = URLDecoder.decode(url, 'UTF-8').stripEnd('/')
        return !sas ? url : "${url}?${sas}"
    }

    static BlobContainerSasPermission CONTAINER_PERMS = new BlobContainerSasPermission()
            .setAddPermission(true)
            .setCreatePermission(true)
            .setDeletePermission(true)
            .setListPermission(true)
            .setMovePermission(true)
            .setReadPermission(true)
            .setTagsPermission(true)
            .setWritePermission(true)

    static BlobSasPermission BLOB_PERMS = new BlobSasPermission()
            .setAddPermission(true)
            .setCreatePermission(true)
            .setDeletePermission(true)
            .setListPermission(true)
            .setMovePermission(true)
            .setReadPermission(true)
            .setTagsPermission(true)
            .setWritePermission(true)

    static AccountSasPermission ACCOUNT_PERMS = new AccountSasPermission()
            .setAddPermission(true)
            .setCreatePermission(true)
            .setDeletePermission(true)
            .setListPermission(true)
            .setReadPermission(true)
            .setTagsPermission(true)
            .setWritePermission(true)
            .setUpdatePermission(true)

    static AccountSasService ACCOUNT_SERVICES = new AccountSasService()
            .setBlobAccess(true)
            .setFileAccess(true)

    static AccountSasResourceType ACCOUNT_RESOURCES = new AccountSasResourceType()
            .setContainer(true)
            .setObject(true)
            .setService(true)


    static String generateContainerSasWithActiveDirectory(Path path, Duration duration) {
        final key = generateUserDelegationKey(az0(path), duration)

        return generateContainerUserDelegationSas(az0(path).containerClient(), duration, key)
    }

    static String generateAccountSasWithAccountKey(Path path, Duration duration) {
        generateAccountSas(az0(path).getFileSystem().getBlobServiceClient(), duration)
    }

    static UserDelegationKey generateUserDelegationKey(Path path, Duration duration) {

        final client = az0(path).getFileSystem().getBlobServiceClient()

        final startTime = OffsetDateTime.now()
        final indicatedExpiryTime = startTime.plusHours(duration.toHours())

        // The maximum lifetime for user delegation key (and therefore delegation SAS) is 7 days
        // Reference https://docs.microsoft.com/en-us/azure/storage/blobs/storage-blob-user-delegation-sas-create-cli
        final maxExpiryTime = startTime.plusDays(7)

        final expiryTime = (indicatedExpiryTime.toEpochSecond() <= maxExpiryTime.toEpochSecond()) ? indicatedExpiryTime : maxExpiryTime

        final delegationKey = client.getUserDelegationKey(startTime, expiryTime)

        return delegationKey
    }

        static String generateContainerUserDelegationSas(BlobContainerClient client, Duration duration, UserDelegationKey key) {
       
        final startTime = OffsetDateTime.now()
        final indicatedExpiryTime = startTime.plusHours(duration.toHours())

        // The maximum lifetime for user delegation key (and therefore delegation SAS) is 7 days
        // Reference https://docs.microsoft.com/en-us/azure/storage/blobs/storage-blob-user-delegation-sas-create-cli
        final maxExpiryTime = startTime.plusDays(7)

        final expiryTime = (indicatedExpiryTime.toEpochSecond() <= maxExpiryTime.toEpochSecond()) ? indicatedExpiryTime : maxExpiryTime

        final signature = new BlobServiceSasSignatureValues()
                .setPermissions(BLOB_PERMS)
                .setPermissions(CONTAINER_PERMS)
                .setStartTime(startTime)
                .setExpiryTime(expiryTime)

        final generatedSas = client.generateUserDelegationSas(signature, key)

        return generatedSas
    }

    static String generateAccountSas(BlobServiceClient client, Duration duration) {
        final expiryTime = OffsetDateTime.now().plusSeconds(duration.toSeconds());
        final signature = new AccountSasSignatureValues(
                expiryTime,
                ACCOUNT_PERMS,
                ACCOUNT_SERVICES,
                ACCOUNT_RESOURCES)

        return client.generateAccountSas(signature)
    }

    static String generateAccountSas(String accountName, String accountKey, Duration duration) {
        final client = getOrCreateBlobServiceWithKey(accountName, accountKey)
        return generateAccountSas(client, duration)
    }

    @Memoized
    static synchronized BlobServiceClient getOrCreateBlobServiceWithKey(String accountName, String accountKey) {
        log.debug "Creating Azure blob storage client -- accountName=$accountName; accountKey=${accountKey?.substring(0,5)}.."

        final credential = new StorageSharedKeyCredential(accountName, accountKey);
        final endpoint = String.format(Locale.ROOT, "https://%s.blob.core.windows.net", accountName);

        Map<String,String> env = new HashMap<>(System.getenv())
        Integer maxTries = env.get("NXF_AZURE_REQUEST_RETRY_COUNT") ? env.get("NXF_AZURE_REQUEST_RETRY_COUNT") as Integer : 10
        Integer tryTimeoutInSeconds = env.get("NXF_AZURE_REQUEST_RESPONSE_TIMEOUT_SECONDS") ? env.get("NXF_AZURE_REQUEST_RESPONSE_TIMEOUT_SECONDS") as Integer : 60
        log.debug "maxTries=${maxTries}"
        log.debug "tryTimoutInSecions=${tryTimeoutInSeconds}"

        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .retryOptions(new RequestRetryOptions(RetryPolicyType.EXPONENTIAL, maxTries, tryTimeoutInSeconds, null, null, null))
                .buildClient()

        log.debug "Created blobServiceClient=${blobServiceClient}"
        for (int i = 0; i < blobServiceClient.getHttpPipeline().getPolicyCount(); i++) {
            def policy = blobServiceClient.getHttpPipeline().getPolicy(i)
            log.debug "In blobServcieClient, httpPipeline policy number ${i} = ${policy}"
        }

        return blobServiceClient
    }

    @Memoized
    static synchronized BlobServiceClient getOrCreateBlobServiceWithToken(String accountName, String sasToken) {
        if( !sasToken )
            throw new IllegalArgumentException("Missing Azure blob SAS token")
        if( sasToken.length()<100 )
            throw new IllegalArgumentException("Invalid Azure blob SAS token -- offending value: $sasToken")

        log.debug "Creating Azure blob storage client -- accountName: $accountName; sasToken: ${sasToken?.substring(0,10)}.."

        final endpoint = String.format(Locale.ROOT, "https://%s.blob.core.windows.net", accountName);

        return new BlobServiceClientBuilder()
                .endpoint(endpoint)
                .sasToken(sasToken)
                .buildClient()
    }

}
