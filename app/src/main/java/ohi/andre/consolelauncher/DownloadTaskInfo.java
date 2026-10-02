package ohi.andre.consolelauncher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public class DownloadTaskInfo implements Serializable {
    private static final long serialVersionUID = 2L;   // bumped for the new field

    public String gid;
    public String url;
    public String savePath;
    public String userAgent;
    public String referer;
    public long totalSize;
    public int threadCount;
    public long[] startBytes;
    public long[] endBytes;
    public long[] downloadedBytes;

    // ★ NEW: cumulative active download time across all resume sessions
    public long elapsedMs = 0L;

    public DownloadTaskInfo(String gid, String url, String savePath,
                            long totalSize, int threadCount) {
        this.gid = gid;
        this.url = url;
        this.savePath = savePath;
        this.totalSize = totalSize;
        this.threadCount = threadCount;
        this.startBytes = new long[threadCount];
        this.endBytes = new long[threadCount];
        this.downloadedBytes = new long[threadCount];
    }

    public long totalDownloaded() {
        long t = 0;
        for (long b : downloadedBytes) t += b;
        return t;
    }

    public boolean isComplete() {
        return totalDownloaded() >= totalSize && totalSize > 0;
    }

    public void saveState(String stateFilePath) {
        try (ObjectOutputStream oos =
                     new ObjectOutputStream(new FileOutputStream(stateFilePath))) {
            oos.writeObject(this);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static DownloadTaskInfo loadState(String stateFilePath) {
        File file = new File(stateFilePath);
        if (!file.exists()) return null;
        try (ObjectInputStream ois =
                     new ObjectInputStream(new FileInputStream(stateFilePath))) {
            return (DownloadTaskInfo) ois.readObject();
        } catch (Exception e) {
            return null;
        }
    }
}