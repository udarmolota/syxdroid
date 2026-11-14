package com.udarmolota.syxdroid;

public interface TaskProgressListener {
    void onProgressUpdate(String message, int progress, int progressMax);
}
