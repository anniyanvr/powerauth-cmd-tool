/*
 * PowerAuth Command-line utility
 * Copyright 2018 Wultra s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.getlime.security.powerauth.lib.cmd.steps.v2;

import com.google.common.io.BaseEncoding;
import com.wultra.core.rest.client.base.RestClient;
import com.wultra.core.rest.client.base.RestClientException;
import io.getlime.core.rest.model.base.request.ObjectRequest;
import io.getlime.core.rest.model.base.response.ObjectResponse;
import io.getlime.security.powerauth.crypto.client.encryptor.ClientNonPersonalizedEncryptor;
import io.getlime.security.powerauth.crypto.lib.encryptor.model.NonPersonalizedEncryptedMessage;
import io.getlime.security.powerauth.lib.cmd.logging.StepLogger;
import io.getlime.security.powerauth.lib.cmd.steps.BaseStep;
import io.getlime.security.powerauth.lib.cmd.steps.model.EncryptStepModel;
import io.getlime.security.powerauth.lib.cmd.util.HttpUtil;
import io.getlime.security.powerauth.lib.cmd.util.MapUtil;
import io.getlime.security.powerauth.lib.cmd.util.RestClientFactory;
import io.getlime.security.powerauth.rest.api.model.entity.NonPersonalizedEncryptedPayloadModel;
import org.json.simple.JSONObject;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Encrypt step encrypts request data using non-personalized end-to-end encryption.
 *
 * <p><b>PowerAuth protocol versions:</b>
 * <ul>
 *     <li>2.0</li>
 *     <li>2.1</li>
 * </ul>
 *
 * @author Roman Strobl, roman.strobl@wultra.com
 *
 */
public class EncryptStep implements BaseStep {

    /**
     * Execute this step with given context.
     * @param stepLogger Step logger.
     * @param context Provided context.
     * @return Result status object, null in case of failure.
     * @throws Exception In case of any error.
     */
    @SuppressWarnings("unchecked")
    public JSONObject execute(StepLogger stepLogger, Map<String, Object> context) throws Exception {

        // Read properties from "context"
        EncryptStepModel model = new EncryptStepModel();
        model.fromMap(context);

        if (stepLogger != null) {
            stepLogger.writeItem(
                    "encrypt-start",
                    "Encrypt Request Started",
                    null,
                    "OK",
                    null
            );
        }

        // Prepare the encryption URI
        String uri = model.getUriString();

        // Read data which needs to be encrypted
        final byte[] requestDataBytes = model.getData();
        if (requestDataBytes == null) {
            if (stepLogger != null) {
                stepLogger.writeError("encrypt-error-data-file", "Encrypt Request Failed", "Request data for encryption was null.");
                stepLogger.writeDoneFailed("encrypt-failed");
            }
            return null;
        }
        // Prepare the encryptor
        ClientNonPersonalizedEncryptor encryptor = new ClientNonPersonalizedEncryptor(BaseEncoding.base64().decode(model.getApplicationKey()), model.getMasterPublicKey());

        // Encrypt the request data
        final NonPersonalizedEncryptedMessage encryptedMessage = encryptor.encrypt(requestDataBytes);
        if (encryptedMessage == null) {
            if (stepLogger != null) {
                stepLogger.writeError("encrypt-error-missing-message", "Encryption failed", "Encrypted message is not available");
                stepLogger.writeDoneFailed("encrypt-failed");
            }
            return null;
        }

        NonPersonalizedEncryptedPayloadModel encryptedRequestObject = new NonPersonalizedEncryptedPayloadModel();
        encryptedRequestObject.setAdHocIndex(BaseEncoding.base64().encode(encryptedMessage.getAdHocIndex()));
        encryptedRequestObject.setApplicationKey(BaseEncoding.base64().encode(encryptedMessage.getApplicationKey()));
        encryptedRequestObject.setEncryptedData(BaseEncoding.base64().encode(encryptedMessage.getEncryptedData()));
        encryptedRequestObject.setEphemeralPublicKey(BaseEncoding.base64().encode(encryptedMessage.getEphemeralPublicKey()));
        encryptedRequestObject.setMac(BaseEncoding.base64().encode(encryptedMessage.getMac()));
        encryptedRequestObject.setMacIndex(BaseEncoding.base64().encode(encryptedMessage.getMacIndex()));
        encryptedRequestObject.setNonce(BaseEncoding.base64().encode(encryptedMessage.getNonce()));
        encryptedRequestObject.setSessionIndex(BaseEncoding.base64().encode(encryptedMessage.getSessionIndex()));

        ObjectRequest<NonPersonalizedEncryptedPayloadModel> body = new ObjectRequest<>();
        body.setRequestObject(encryptedRequestObject);

        if (stepLogger != null) {
            stepLogger.writeItem(
                    "encrypt-request-encrypt",
                    "Encrypting request data",
                    "Following data is sent to intermediate server",
                    "OK",
                    body
            );
        }

        try {

            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            headers.put("Content-Type", "application/json");
            headers.putAll(model.getHeaders());

            if (stepLogger != null) {
                stepLogger.writeServerCall("encrypt-request-sent", uri, "POST", body, headers);
            }

            ResponseEntity<ObjectResponse<NonPersonalizedEncryptedPayloadModel>> responseEntity;
            RestClient restClient = RestClientFactory.getRestClient();
            if (restClient == null) {
                return null;
            }
            ParameterizedTypeReference<ObjectResponse<NonPersonalizedEncryptedPayloadModel>> typeReference = new ParameterizedTypeReference<ObjectResponse<NonPersonalizedEncryptedPayloadModel>>() {};
            try {
                responseEntity = restClient.post(uri, body, null, MapUtil.toMultiValueMap(headers), typeReference);
            } catch (RestClientException ex) {
                if (stepLogger != null) {
                    stepLogger.writeServerCallError("encrypt-error-server-call", ex.getStatusCode().value(), ex.getResponse(), HttpUtil.flattenHttpHeaders(ex.getResponseHeaders()));
                    stepLogger.writeDoneFailed("encrypt-failed");
                }
                return null;
            }

            ObjectResponse<NonPersonalizedEncryptedPayloadModel> responseWrapper = Objects.requireNonNull(responseEntity.getBody());

            if (stepLogger != null) {
                stepLogger.writeServerCallOK("encrypt-response-received", responseWrapper, HttpUtil.flattenHttpHeaders(responseEntity.getHeaders()));
            }

            // Decrypt the server response
            final NonPersonalizedEncryptedPayloadModel encryptedResponseObject = responseWrapper.getResponseObject();
            encryptedMessage.setApplicationKey(BaseEncoding.base64().decode(encryptedResponseObject.getApplicationKey()));
            encryptedMessage.setAdHocIndex(BaseEncoding.base64().decode(encryptedResponseObject.getAdHocIndex()));
            encryptedMessage.setEphemeralPublicKey(BaseEncoding.base64().decode(encryptedResponseObject.getEphemeralPublicKey()));
            encryptedMessage.setEncryptedData(BaseEncoding.base64().decode(encryptedResponseObject.getEncryptedData()));
            encryptedMessage.setMac(BaseEncoding.base64().decode(encryptedResponseObject.getMac()));
            encryptedMessage.setMacIndex(BaseEncoding.base64().decode(encryptedResponseObject.getMacIndex()));
            encryptedMessage.setNonce(BaseEncoding.base64().decode(encryptedResponseObject.getNonce()));
            encryptedMessage.setSessionIndex(BaseEncoding.base64().decode(encryptedResponseObject.getSessionIndex()));

            byte[] decryptedMessageBytes = encryptor.decrypt(encryptedMessage);
            if (decryptedMessageBytes == null) {
                if (stepLogger != null) {
                    stepLogger.writeError("encrypt-error-decrypt", "Decryption failed", "Decrypted message is not available");
                    stepLogger.writeDoneFailed("encrypt-failed");
                }
                return null;
            }

            String decryptedMessage = new String(decryptedMessageBytes, StandardCharsets.UTF_8);
            model.getResultStatusObject().put("responseData", decryptedMessage);

            if (stepLogger != null) {
                stepLogger.writeItem(
                        "encrypt-response-decrypt",
                        "Decrypted response",
                        "Following data were decrypted",
                        "OK",
                        decryptedMessage
                );
                stepLogger.writeDoneOK("encrypt-success");
            }
            return model.getResultStatusObject();
        } catch (Exception exception) {
            if (stepLogger != null) {
                stepLogger.writeError("encrypt-error-generic", exception);
                stepLogger.writeDoneFailed("encrypt-failed");
            }
            return null;
        }
    }

}
