package de.timklge.karooroutegraph.pois

enum class PbfDownloadStatus {
    /** Scheduled for download */
    PENDING,
    /** Download has failed, manual restart required */
    DOWNLOAD_FAILED,
    /** Downloaded and ready for use */
    AVAILABLE,
    /** Update available */
    UPDATE_AVAILABLE,
    /** Update scheduled */
    UPDATING
}