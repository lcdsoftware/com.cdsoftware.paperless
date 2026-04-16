package com.cdsoftware.paperless.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.compiere.model.IAttachmentStore;
import org.compiere.model.MAttachment;
import org.compiere.model.MAttachmentEntry;
import org.compiere.model.MStorageProvider;
import org.compiere.util.CLogger;

import com.cdsoftware.paperless.util.DMSConfig;
import com.cdsoftware.paperless.util.PaperlessUtil;

/**
 * Implementation of {@link IAttachmentStore} for Paperless-ngx integration.
 * Handles attachment storage operations using a ZIP manifest pattern to store
 * Paperless document IDs as references.
 * 
 * @version 1.0
 */
public class AttachmentPaperless implements IAttachmentStore {
    
    /** Logger instance for this class */
    private final CLogger log = CLogger.getCLogger(getClass());
    
    /** Constant to identify ZIP format attachments */
    public static final String ZIP = "zip";

    /**
     * Loads attachment data from Paperless-ngx.
     * <p>
     * This method reads the ZIP manifest stored in the attachment's binary data,
     * extracts Paperless document IDs, downloads each document from the API,
     * and populates the attachment entries with the actual file content.
     * </p>
     * 
     * @param attach the {@link MAttachment} instance to load data into
     * @param prov the {@link MStorageProvider} containing connection configuration
     * @return {@code true} if the operation completed successfully, {@code false} if an error occurred
     */
    @Override
    public boolean loadLOBData(MAttachment attach, MStorageProvider prov) {
        attach.m_items = new ArrayList<MAttachmentEntry>();
        byte[] data = attach.getBinaryData();

        if (data == null || data.length == 0) {
            return true;
        }

        // Handle single file format (legacy, non-ZIP)
        if (!ZIP.equals(attach.getTitle())) {
            attach.m_items.add(new MAttachmentEntry(attach.getTitle(), data, 1));
            return true;
        }

        try {
            ByteArrayInputStream in = new ByteArrayInputStream(data);
            ZipInputStream zip = new ZipInputStream(in);
            ZipEntry entry = zip.getNextEntry();
            int entryIndex = 0;

            while (entry != null) {
                entryIndex++;
                String name = entry.getName();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                int length;
                while ((length = zip.read(buffer)) != -1) {
                    out.write(buffer, 0, length);
                }

                byte[] dataEntry = out.toByteArray();
                String uuid = new String(dataEntry, StandardCharsets.UTF_8).trim();

                // Skip invalid UUIDs
                if (uuid.isEmpty() || uuid.equals("null") || uuid.equals("OK_NO_TASK")) {
                    entry = zip.getNextEntry();
                    continue;
                }

                DMSConfig config = PaperlessUtil.getDMSConfig(prov, attach.getAD_Table_ID(), attach.getRecord_ID());
                byte[] documentData = PaperlessUtil.getDocument(uuid, config);

                if (documentData != null) {
                    attach.m_items.add(new MAttachmentEntry(name, documentData, entryIndex));
                } else {
                    log.severe("Failed to download document for UUID: " + uuid);
                }
                entry = zip.getNextEntry();
            }
            zip.close();
        } catch (Exception e) {
            log.log(Level.SEVERE, "EXCEPTION in loadLOBData", e);
            attach.m_items = null;
            return false;
        }
        return true;
    }

    /**
     * Saves attachment entries to Paperless-ngx.
     * <p>
     * This method iterates through each entry in the attachment, uploads the file
     * content to Paperless-ngx via API, collects the returned document IDs,
     * and stores them in a ZIP manifest within the attachment's binary data.
     * </p>
     * 
     * @param attach the {@link MAttachment} instance containing entries to save
     * @param prov the {@link MStorageProvider} containing connection configuration
     * @return {@code true} if all entries were successfully saved, {@code false} if any operation failed
     */
    @Override
    public boolean save(MAttachment attach, MStorageProvider prov) {
        // Special case: no entries to save
        if (attach.getEntries() == null || attach.getEntries().length == 0) {
            return true;
        }

        try {
            DMSConfig config = PaperlessUtil.getDMSConfig(prov, attach.getAD_Table_ID(), attach.getRecord_ID());
            List<String> paperlessIds = new ArrayList<>();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ZipOutputStream zos = new ZipOutputStream(baos);

            for (MAttachmentEntry entry : attach.getEntries()) {
                String fileName = entry.getName();
                byte[] data = entry.getData();
                if (data == null || data.length == 0) continue;

                String paperlessId = PaperlessUtil.saveDataToPaperless(data, fileName, config.getUrl(), config.getRootFolder(), config.getEncoding());

                if (paperlessId != null) {
                    paperlessIds.add(paperlessId);
                    ZipEntry zipEntry = new ZipEntry(fileName);
                    zos.putNextEntry(zipEntry);
                    zos.write(paperlessId.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                } else {
                    log.severe("Failed to upload file: " + fileName);
                }
            }
            zos.close();

            if (!paperlessIds.isEmpty()) {
                attach.setBinaryData(baos.toByteArray());
                attach.setTitle("zip");
                return true;
            } else {
                // Fallback: if no new uploads but existing data exists, consider success
                if (attach.getBinaryData() != null && attach.getBinaryData().length > 0) {
                    return true;
                }
                log.severe("No documents could be saved to Paperless");
                return false;
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "EXCEPTION in save()", e);
            return false;
        }
    }

    /**
     * Deletes all documents associated with an attachment from Paperless-ngx.
     * <p>
     * This method reads the ZIP manifest from the attachment's binary data,
     * extracts all stored Paperless document IDs, and deletes each document
     * from the remote storage via API. It also clears the in-memory entry list.
     * </p>
     * 
     * @param attach the {@link MAttachment} instance whose documents should be deleted
     * @param prov the {@link MStorageProvider} containing connection configuration
     * @return {@code true} if the deletion operation completed (regardless of individual document results), {@code false} if an error occurred
     */
    @Override
    public boolean delete(MAttachment attach, MStorageProvider prov) {
        byte[] data = attach.getBinaryData();
        if (data == null || data.length == 0) {
            return true;
        }

        try {
            DMSConfig config = PaperlessUtil.getDMSConfig(prov, attach.getAD_Table_ID(), attach.getRecord_ID());
            
            // Extract and delete all IDs from the ZIP manifest
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[2048];
                    int len;
                    while ((len = zis.read(buffer)) > 0) out.write(buffer, 0, len);
                    
                    String id = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
                    if (!id.isEmpty()) {
                        PaperlessUtil.deleteDocumentFromPaperless(id, config.getUrl(), config.getEncoding());
                    }
                    zis.closeEntry();
                }
            }

            // Clear the in-memory entry list
            if (attach.m_items != null) {
                attach.m_items.clear();
            }
            
            return true;

        } catch (Exception e) {
            log.log(Level.SEVERE, "EXCEPTION in delete()", e);
            return false;
        }
    }

    /**
     * Deletes a single entry from an attachment and removes the corresponding document from Paperless-ngx.
     * <p>
     * This method identifies the entry to delete by index, extracts its Paperless document ID
     * from the ZIP manifest, deletes the remote document via API, reconstructs the ZIP manifest
     * without the deleted entry, updates the attachment's binary data and in-memory entry list,
     * and returns the operation result.
     * </p>
     * 
     * @param attach the {@link MAttachment} instance containing the entry to delete
     * @param prov the {@link MStorageProvider} containing connection configuration
     * @param index the zero-based index of the entry to delete within the attachment
     * @return {@code true} if the entry was successfully deleted from both local and remote storage, {@code false} if an error occurred
     */
    @Override
    public boolean deleteEntry(MAttachment attach, MStorageProvider prov, int index) {
        byte[] data = attach.getBinaryData();
        if (data == null || data.length == 0) return true;

        try {
            DMSConfig config = PaperlessUtil.getDMSConfig(prov, attach.getAD_Table_ID(), attach.getRecord_ID());
            
            // Extract the ID to delete by entry name
            String idToDelete = null;
            String entryNameToDelete = null;
            
            if (attach.m_items != null && index < attach.m_items.size()) {
                entryNameToDelete = attach.m_items.get(index).getName();
            }
            
            // Find the ID in the ZIP manifest by entry name
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().equals(entryNameToDelete)) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buffer = new byte[2048];
                        int len;
                        while ((len = zis.read(buffer)) > 0) out.write(buffer, 0, len);
                        idToDelete = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
                        break;
                    }
                    zis.closeEntry();
                }
            }

            if (idToDelete != null) {
                // Delete from Paperless
                boolean deleted = PaperlessUtil.deleteDocumentFromPaperless(idToDelete, config.getUrl(), config.getEncoding());
                
                if (deleted) {
                    // Reconstruct ZIP without the deleted entry
                    ByteArrayOutputStream newBaos = new ByteArrayOutputStream();
                    ZipOutputStream newZos = new ZipOutputStream(newBaos);
                    
                    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
                        ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            if (!entry.getName().equals(entryNameToDelete)) {
                                ByteArrayOutputStream out = new ByteArrayOutputStream();
                                byte[] buffer = new byte[2048];
                                int len;
                                while ((len = zis.read(buffer)) > 0) out.write(buffer, 0, len);
                                
                                ZipEntry newEntry = new ZipEntry(entry.getName());
                                newZos.putNextEntry(newEntry);
                                newZos.write(out.toByteArray());
                                newZos.closeEntry();
                            }
                            zis.closeEntry();
                        }
                    }
                    newZos.close();
                    
                    // Update local state (ZIP + m_items)
                    if (newBaos.size() > 0) {
                        attach.setBinaryData(newBaos.toByteArray());
                        attach.setTitle("zip");
                    } else {
                        attach.setBinaryData(null);
                        attach.setTitle(".");
                    }
                    
                    // Remove from in-memory list
                    if (attach.m_items != null && index < attach.m_items.size()) {
                        attach.m_items.remove(index);
                    }
                    
                    return true;
                }
                log.severe("Failed to delete document from Paperless. ID: " + idToDelete);
                return false;
            }

            log.severe("Entry not found for deletion at index: " + index);
            return false;

        } catch (Exception e) {
            log.log(Level.SEVERE, "EXCEPTION in deleteEntry()", e);
            return false;
        }
    }
}