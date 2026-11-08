package com.example.hotrelay.model;

import java.io.Serializable;

public class TransferItem implements Serializable {

    private String transferId;
    private String fileName;
    private long fileSize;
    private String formattedSize;
    private int progress; // 0 - 100
    private TransferStatus status;
    private String recipientName;
    private String senderName;
    private boolean isOutgoing;

    public TransferItem(String transferId, String fileName, long fileSize, String formattedSize,
                        int progress, TransferStatus status, String recipientName, String senderName, boolean isOutgoing) {
        this.transferId = transferId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.formattedSize = formattedSize;
        this.progress = progress;
        this.status = status;
        this.recipientName = recipientName;
        this.senderName = senderName;
        this.isOutgoing = isOutgoing;
    }

    public String getTransferId() {
        return transferId;
    }

    public String getFileName() {
        return fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getFormattedSize() {
        return formattedSize;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public void setStatus(TransferStatus status) {
        this.status = status;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getSenderName() {
        return senderName;
    }

    public boolean isOutgoing() {
        return isOutgoing;
    }
}