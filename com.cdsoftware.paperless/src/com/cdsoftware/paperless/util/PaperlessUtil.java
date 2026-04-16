package com.cdsoftware.paperless.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.compiere.model.MClient;
import org.compiere.model.MStorageProvider;
import org.compiere.model.MTable;
import org.compiere.util.Env;
import org.compiere.util.CLogger;

/**
 * Utility class for interacting with Paperless-ngx API.
 * <p>
 * Provides methods for uploading, downloading, and deleting documents
 * via the Paperless-ngx REST API using Basic Authentication.
 * Handles asynchronous task polling and response parsing.
 * </p>
 * 
 * @author Your Name
 * @version 1.0
 */
public class PaperlessUtil {
    
    /** Logger instance for this class */
    private static final CLogger log = CLogger.getCLogger(PaperlessUtil.class);
    
    /**
     * Loads data from external storage.
     * <p>
     * This method is currently not implemented and returns {@code false}.
     * </p>
     * 
     * @return {@code false} always, as the method is not implemented
     */
    public boolean loadData() {
        return false;
    } 
    
    /**
     * Saves data to Paperless-ngx via API using Basic Authentication.
     * <p>
     * This method uploads a file to Paperless-ngx using the {@code /api/documents/post_document/}
     * endpoint. It handles multipart form submission, parses the JSON response
     * (supporting object, array, or string literal formats), extracts the task or document ID,
     * and polls the task endpoint until completion or timeout.
     * </p>
     * 
     * @param data the file content as {@code byte[]}
     * @param fileName the file name with extension
     * @param url the base URL of Paperless API (e.g., {@code http://localhost:8000/})
     * @param path optional folder path (not used in basic implementation)
     * @param base64Auth Base64 encoded credentials for Basic Auth ({@code user:pass})
     * @return the Paperless {@code task_id} or {@code document_id} as {@code String}, or {@code null} if the operation failed
     * @throws IOException if the HTTP connection fails or response parsing encounters an error
     */
    public static String saveDataToPaperless(byte[] data, String fileName, String url, String path, String base64Auth) throws IOException {

        String baseUrl = url.endsWith("/") ? url : url + "/";
        String endpoint = baseUrl + "api/documents/post_document/";

        URL obj = new URL(endpoint);
        HttpURLConnection con = (HttpURLConnection) obj.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("Authorization", "Basic " + base64Auth);
        con.setDoOutput(true);
        con.setConnectTimeout(30000);
        con.setReadTimeout(30000);
        con.setInstanceFollowRedirects(true);

        String boundary = "----PaperlessUpload" + System.currentTimeMillis();
        con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream os = con.getOutputStream()) {
            os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(("Content-Disposition: form-data; name=\"document\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            String contentType = fileName.toLowerCase().endsWith(".pdf") ? "application/pdf" : "application/octet-stream";
            os.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(data);
            os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = con.getResponseCode();

        if (responseCode != 200 && responseCode != 201) {
            String err = con.getErrorStream() != null
                ? new String(con.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
                : "Unknown";
            log.severe("Failed to upload to Paperless (HTTP " + responseCode + "): " + err);
            return null;
        }

        // Read complete response safely
        String fullResponse = "";
        try {
            byte[] rawBytes = con.getInputStream().readAllBytes();
            fullResponse = new String(rawBytes, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            log.severe("Error reading response stream: " + e.getMessage());
            return null;
        }

        if (fullResponse.isEmpty()) {
            log.severe("HTTP 200 received but response body is empty");
            return null;
        }

        // Extract task_id or document_id from response
        String taskId = null;
        if (fullResponse.contains("\"task_id\"")) {
            int idx = fullResponse.indexOf("\"task_id\":");
            taskId = idx >= 0 ? fullResponse.substring(idx + 10).replaceAll("[\"\\s,}]", "") : null;
        } else if (fullResponse.contains("\"document_id\"")) {
            int idx = fullResponse.indexOf("\"document_id\":");
            taskId = idx >= 0 ? fullResponse.substring(idx + 13).replaceAll("[\"\\s,}]", "") : null;
        } else if (fullResponse.matches("\\d+")) {
            taskId = fullResponse;
        } 
        // Handle JSON string literal: "uuid-here"
        else if (fullResponse.startsWith("\"") && fullResponse.endsWith("\"") && fullResponse.length() > 2) {
            taskId = fullResponse.substring(1, fullResponse.length() - 1);
        }

        if (taskId != null && !taskId.isEmpty()) {
            return waitForTaskCompletion(taskId, baseUrl, base64Auth);
        }

        log.severe("Could not extract valid ID from Paperless response");
        return null;
    }

    /**
     * Polls the Paperless-ngx task endpoint until the task completes or times out.
     * <p>
     * This method supports responses in array format {@code [{"..."}]} or object format {@code {...}}.
     * It handles SUCCESS, FAILURE (including duplicate detection), and intermediate states.
     * </p>
     * 
     * @param taskId the task UUID returned by {@code post_document}
     * @param baseUrl the base URL of Paperless API
     * @param base64Auth Base64 encoded credentials for Basic Auth
     * @return the numeric {@code document_id} if successful, or the {@code task_id} as fallback if timeout occurs, or {@code null} if failed
     * @throws IOException if the HTTP connection fails during polling
     */
    private static String waitForTaskCompletion(String taskId, String baseUrl, String base64Auth) throws IOException {
        // If already numeric, no polling needed
        if (taskId.matches("\\d+")) {
            return taskId;
        }

        long start = System.currentTimeMillis();
        int attempts = 0;
        
        // Timeout set to 45 seconds (Paperless may take time with OCR)
        while (System.currentTimeMillis() - start < 45000) {
            attempts++;
            
            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }

            String taskEndpoint = baseUrl + "api/tasks/?task_id=" + taskId;
            URL taskUrl = new URL(taskEndpoint);
            HttpURLConnection con = (HttpURLConnection) taskUrl.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("Authorization", "Basic " + base64Auth);
            con.setConnectTimeout(10000);
            con.setReadTimeout(10000);

            int taskResponseCode = con.getResponseCode();
            
            if (taskResponseCode == 200) {
                String resp = new String(con.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                
                // Normalize: if array, take first element
                String taskObj = resp;
                if (resp.startsWith("[") && resp.endsWith("]")) {
                    int firstObjStart = resp.indexOf('{');
                    int firstObjEnd = resp.lastIndexOf('}');
                    if (firstObjStart >= 0 && firstObjEnd > firstObjStart) {
                        taskObj = resp.substring(firstObjStart, firstObjEnd + 1);
                    }
                }
                
                // SUCCESS case: extract related_document or document_id
                if (taskObj.contains("\"status\":\"SUCCESS\"")) {
                    int dIdx = taskObj.indexOf("\"related_document\":");
                    if (dIdx == -1) {
                        dIdx = taskObj.indexOf("\"document_id\":");
                    }
                    if (dIdx > 0) {
                        int vStart = taskObj.indexOf(":", dIdx) + 1;
                        while (vStart < taskObj.length() && !Character.isDigit(taskObj.charAt(vStart))) vStart++;
                        int vEnd = vStart;
                        while (vEnd < taskObj.length() && Character.isDigit(taskObj.charAt(vEnd))) vEnd++;
                        return taskObj.substring(vStart, vEnd);
                    }
                } 
                // FAILURE case: handle "duplicate" even if in trash
                else if (taskObj.contains("\"status\":\"FAILURE\"")) {
                    // Extract ID from pattern: "duplicate of ... (#123)"
                    if (taskObj.contains("It is a duplicate of") || taskObj.contains("duplicate")) {
                        int hashIdx = taskObj.indexOf("(#");
                        if (hashIdx > 0) {
                            int idStart = hashIdx + 2;
                            int idEnd = taskObj.indexOf(")", idStart);
                            if (idEnd > idStart) {
                                String existingId = taskObj.substring(idStart, idEnd).trim();
                                if (existingId.matches("\\d+")) {
                                    return existingId;
                                }
                            }
                        }
                    }
                    log.severe("Task failed: " + extractErrorMessage(taskObj));
                    return null;
                } 
                // Intermediate case (PENDING/STARTED): continue polling
            } else {
                String err = con.getErrorStream() != null 
                    ? new String(con.getErrorStream().readAllBytes(), StandardCharsets.UTF_8) 
                    : "Unknown";
                log.severe("Task endpoint error (HTTP " + taskResponseCode + "): " + err);
            }
        }
        
        log.severe("Timeout waiting for task completion. Task ID: " + taskId);
        return taskId;
    }
    
    /**
     * Helper method to extract error message from task response JSON.
     * 
     * @param taskObj the normalized task response object as String
     * @return the error message or a default description
     */
    private static String extractErrorMessage(String taskObj) {
        int resultIdx = taskObj.indexOf("\"result\":");
        if (resultIdx > 0) {
            int rStart = taskObj.indexOf(":", resultIdx) + 2;
            int rEnd = taskObj.indexOf("\"", rStart);
            if (rEnd > rStart) {
                return taskObj.substring(rStart, rEnd);
            }
        }
        return "Unknown error";
    }

    /**
     * Checks in a document to external storage.
     * <p>
     * This method is currently not implemented.
     * </p>
     * 
     * @param data the data (unused)
     * @param fileName the file name (unused)
     * @param url the URL (unused)
     * @param path the path (unused)
     * @param encoding the encoding (unused)
     * @param uuid the UUID (unused)
     * @throws IOException if an I/O error occurs (not thrown in current implementation)
     */
    private void checkIn(byte[] data, String fileName, String url, String path, String encoding, String uuid) throws IOException {
        // Not implemented
    }

    /**
     * Checks out a document from external storage.
     * <p>
     * This method is currently not implemented.
     * </p>
     * 
     * @param existingUUID the existing UUID (unused)
     * @param url the URL (unused)
     * @param fileName the file name (unused)
     * @param encoding the encoding (unused)
     * @return status code (always 0 in current implementation)
     */
    private int checkOut(String existingUUID, String url, String fileName, String encoding) {
        return 0;
    }

    /**
     * Gets an existing UUID from external storage.
     * <p>
     * This method is currently not implemented.
     * </p>
     * 
     * @param fileName the file name (unused)
     * @param url the URL (unused)
     * @param path the path (unused)
     * @param encoding the encoding (unused)
     * @return {@code null} always, as the method is not implemented
     */
    public static String getExistingUUID(String fileName, String url, String path, String encoding) {
        return null; 
    }

    /**
     * Gets the DMS UUID for a document.
     * <p>
     * This method is currently not implemented.
     * </p>
     * 
     * @param data the data (unused)
     * @param config the configuration (unused)
     * @param dataTitle the data title (unused)
     * @return {@code null} always, as the method is not implemented
     */
    public byte[] getDMSUUID(byte[] data, DMSConfig config, String dataTitle) {
        return null;
    }

    /**
     * Builds a DMS configuration object from a storage provider.
     * 
     * @param prov the {@link MStorageProvider} containing connection settings
     * @param tableId the AD_Table_ID of the referencing record
     * @param recordId the Record_ID of the referencing record
     * @return a populated {@link DMSConfig} instance with API credentials and context
     */
    public static DMSConfig getDMSConfig(MStorageProvider prov, int tableId, int recordId) {
        DMSConfig config = new DMSConfig();
        String authString = prov.getUserName() + ":" + prov.getPassword();
        try {
            String encoded = Base64.getEncoder().encodeToString(authString.getBytes(StandardCharsets.UTF_8));
            config.setEncoding(encoded);
        } catch (Exception e) {
            log.severe("Error encoding credentials: " + e.getMessage());
        }
        config.setUrl(prov.getURL());
        config.setRootFolder(prov.getFolder()); 
        config.setTableName(getTableName(tableId));
        config.setRecordId(recordId);
        return config;
    }
    
    /**
     * Gets the table name for a given AD_Table_ID.
     * 
     * @param tableId the AD_Table_ID to resolve
     * @return the table name in uppercase, or empty string if not found
     */
    public static String getTableName(int tableId) {
        return new MTable(Env.getCtx(), tableId, null).getTableName().toUpperCase();
    }
    
    /**
     * Gets the client name for a given AD_Client_ID.
     * 
     * @param clientId the AD_Client_ID to resolve
     * @return the client name, or empty string if not found
     */
    public static String getClientName(int clientId) {
        return new MClient(Env.getCtx(), clientId, null).getName();
    }
    
    /**
     * Downloads a document from Paperless-ngx via API.
     * 
     * @param uuid the Paperless document ID or UUID
     * @param config the {@link DMSConfig} containing connection settings
     * @return the document content as {@code byte[]}, or {@code null} if the download failed
     */
    public static byte[] getDocument(String uuid, DMSConfig config) {
        byte[] dataEntry = null;
        
        String url = config.getUrl() + "api/documents/" + uuid + "/download/";
        
        try {
            URL obj = new URL(url);
            HttpURLConnection con = (HttpURLConnection) obj.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("Authorization", "Basic " + config.getEncoding());
            int responseCode = con.getResponseCode();
            
            InputStream inputStream;
            if (200 <= responseCode && responseCode <= 299) {
                inputStream = con.getInputStream();
            } else {
                log.severe("Failed to download document (HTTP " + responseCode + "): " + uuid);
                inputStream = con.getErrorStream();
                if (inputStream == null) return null;
            }
 
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] byteChunk = new byte[4096];
            int n;

            while ((n = inputStream.read(byteChunk)) > 0) {
                baos.write(byteChunk, 0, n);
            }

            dataEntry = baos.toByteArray();
        } catch (IOException e) {
            log.severe("Exception downloading document: " + e.getMessage());
        }
        return dataEntry;
    }
    
    /**
     * Checks if a document was deleted by UUID.
     * <p>
     * This method is currently not implemented and always returns {@code true}.
     * </p>
     * 
     * @param uuid the UUID to check (unused)
     * @param config the configuration (unused)
     * @return {@code true} always, as the method is not implemented
     */
    public static boolean isDocsDeleteByUUID(String uuid, DMSConfig config) {
        return true;
    }
    
    /**
     * Gets the client path for storage organization.
     * 
     * @return the client path as {@code /ClientName}
     */
    public static String getClientPath() { 
        return "/" + getClientName(Env.getAD_Client_ID(Env.getCtx()));
    }
    
    /**
     * Logs in to the DMS system.
     * <p>
     * This method is currently not implemented.
     * </p>
     * 
     * @param config the DMS configuration (unused)
     * @return {@code null} always, as the method is not implemented
     */
    public static String loginDMS(DMSConfig config) {
        return null;
    }
    
    /**
     * Decodes Base64 encoded credentials into username and password.
     * 
     * @param encodedAuth the Base64 encoded string in format {@code user:pass}
     * @return a {@code String[]} where {@code [0]} is username and {@code [1]} is password, or {@code null} if decoding fails
     */
    public static String[] decodeCredentials(String encodedAuth) {
        if (encodedAuth == null || encodedAuth.isEmpty()) {
            return null;
        }
        
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(encodedAuth);
            String decodedString = new String(decodedBytes, StandardCharsets.UTF_8);
            return decodedString.split(":", 2);
        } catch (Exception e) {
            log.severe("Error decoding credentials: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Deletes a document from Paperless-ngx via API.
     * 
     * @param documentId the numeric document ID or UUID to delete
     * @param url the base URL of Paperless API
     * @param base64Auth Base64 encoded credentials for Basic Auth
     * @return {@code true} if deletion was successful (HTTP 2xx), {@code false} otherwise
     */
    public static boolean deleteDocumentFromPaperless(String documentId, String url, String base64Auth) {
        if (documentId == null || documentId.trim().isEmpty()) {
            log.severe("Invalid document ID for deletion: null or empty");
            return false;
        }

        String baseUrl = url.endsWith("/") ? url : url + "/";
        String endpoint = baseUrl + "api/documents/" + documentId.trim() + "/";

        try {
            URL obj = new URL(endpoint);
            HttpURLConnection con = (HttpURLConnection) obj.openConnection();
            con.setRequestMethod("DELETE");
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("Authorization", "Basic " + base64Auth);
            con.setConnectTimeout(30000);
            con.setReadTimeout(30000);

            int responseCode = con.getResponseCode();

            if (responseCode >= 200 && responseCode <= 299) {
                return true;
            } else {
                String err = con.getErrorStream() != null 
                    ? new String(con.getErrorStream().readAllBytes(), StandardCharsets.UTF_8) 
                    : "Unknown";
                log.severe("Failed to delete document (HTTP " + responseCode + "): " + err);
                return false;
            }
        } catch (IOException e) {
            log.severe("Exception during deletion: " + e.getMessage());
            return false;
        }
    }
}