package com.cdsoftware.paperless.model;

import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

import org.compiere.model.IArchiveStore;
import org.compiere.model.MArchive;
import org.compiere.model.MStorageProvider;
import org.compiere.util.CLogger;
import org.compiere.util.Trx;

import com.cdsoftware.paperless.util.DMSConfig;
import com.cdsoftware.paperless.util.PaperlessUtil;

/**
 * Implementation of {@link IArchiveStore} for Paperless-ngx integration.
 * Handles archive storage operations by storing Paperless document IDs
 * as references in the archive's binary data field.
 * 
 * @version 1.0
 */
public class ArchivePaperless implements IArchiveStore {
    
    /** Logger instance for this class */
    private final CLogger log = CLogger.getCLogger(getClass());
    
    /**
     * Loads archive data from Paperless-ngx.
     * <p>
     * This method extracts the Paperless document ID (UUID) stored in the
     * archive's binary data field, downloads the corresponding document
     * from the Paperless-ngx API, and returns the file content as a byte array.
     * </p>
     * 
     * @param archive the {@link MArchive} instance containing the document reference
     * @param prov the {@link MStorageProvider} containing connection configuration
     * @return the document content as {@code byte[]}, or {@code null} if the download failed
     */
    @Override
    public byte[] loadLOBData(MArchive archive, MStorageProvider prov) {
        try {
            DMSConfig config = PaperlessUtil.getDMSConfig(prov, archive.getAD_Table_ID(), archive.getRecord_ID());
            
            // Explicit UTF-8 when converting byte[] → String for the UUID
            String uuid = new String(archive.getByteData(), StandardCharsets.UTF_8).trim();
            
            if (uuid.isEmpty() || uuid.equals("null") || uuid.equals("OK_NO_TASK")) {
                return null;
            }
            
            byte[] documentData = PaperlessUtil.getDocument(uuid, config);
            
            if (documentData == null) {
                log.severe("Failed to download document for UUID: " + uuid);
            }
            
            return documentData;
            
        } catch (Exception e) {
            log.log(Level.SEVERE, "EXCEPTION in loadLOBData", e);
            return null;
        }
    }

    /**
     * Saves archive data to Paperless-ngx via API.
     * <p>
     * This method uploads the file content to Paperless-ngx, receives the
     * assigned document ID in response, and stores that ID in the archive's
     * binary data field for future reference and retrieval.
     * </p>
     * 
     * @param archive the {@link MArchive} instance to save
     * @param prov the {@link MStorageProvider} containing connection configuration
     * @param inflatedData the file content as {@code byte[]} to upload
     */
    @Override
    public void save(MArchive archive, MStorageProvider prov, byte[] inflatedData) {
        try {
            if (inflatedData == null || inflatedData.length == 0) {
                return;
            }
            
            DMSConfig config = PaperlessUtil.getDMSConfig(prov, archive.getAD_Table_ID(), archive.getRecord_ID());
            String fileName = archive.getName() != null && !archive.getName().isEmpty() 
                ? archive.getName() 
                : "document_" + archive.getRecord_ID() + ".pdf";
            
            String paperlessId = PaperlessUtil.saveDataToPaperless(inflatedData, fileName, config.getUrl(), config.getRootFolder(), config.getEncoding());
            
            if (paperlessId != null) {
                // Save the ID with explicit UTF-8 encoding
                archive.setByteData(paperlessId.getBytes(StandardCharsets.UTF_8));
                archive.saveEx();
            } else {
                log.severe("Failed to save document to Paperless: " + fileName);
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "EXCEPTION in save()", e);
        }
    }

    /**
     * Deletes an archive document from Paperless-ngx.
     * <p>
     * This method extracts the Paperless document ID (UUID) from the archive's
     * binary data field and sends a DELETE request to the Paperless-ngx API
     * to remove the document from remote storage.
     * </p>
     * 
     * @param archive the {@link MArchive} instance whose remote document should be deleted
     * @param prov the {@link MStorageProvider} containing connection configuration
     * @return {@code true} if the deletion was successful or if no valid reference existed, {@code false} if an error occurred
     */
    @Override
    public boolean deleteArchive(MArchive archive, MStorageProvider prov) {
        try {
            // Extract UUID with explicit UTF-8 encoding
            String uuid = new String(archive.getByteData(), StandardCharsets.UTF_8).trim();
            
            if (uuid == null || uuid.isEmpty() || uuid.equals("null") || uuid.equals("OK_NO_TASK")) {
                return true;
            }
            
            DMSConfig config = PaperlessUtil.getDMSConfig(prov, archive.getAD_Table_ID(), archive.getRecord_ID());
            
            // Delete from Paperless
            boolean deleted = PaperlessUtil.deleteDocumentFromPaperless(uuid, config.getUrl(), config.getEncoding());
            
            if (!deleted) {
                log.severe("Failed to delete document from Paperless. UUID: " + uuid);
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            log.log(Level.SEVERE, "EXCEPTION in deleteArchive()", e);
            return false;
        }
    }

    /**
     * Checks if there are pending changes that require flushing to storage.
     * <p>
     * This implementation does not use a flush mechanism; persistence is
     * handled immediately in the {@link #save(MArchive, MStorageProvider, byte[])} method.
     * </p>
     * 
     * @return {@code false} always, as no pending flush is required
     */
    @Override
    public boolean isPendingFlush() {
        return false;
    }

    /**
     * Flushes any pending changes to persistent storage.
     * <p>
     * No-op implementation: persistence is handled directly in the
     * {@link #save(MArchive, MStorageProvider, byte[])} method.
     * </p>
     * 
     * @param archive the {@link MArchive} instance (unused)
     * @param prov the {@link MStorageProvider} instance (unused)
     */
    @Override
    public void flush(MArchive archive, MStorageProvider prov) {
        // No-op: persistence is handled in save()
    }
}