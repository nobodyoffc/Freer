package com.fc.freer.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 下载管理器 - 提供高级文件下载功能
 */
public class DownloadManager {
    private static final String TAG = "DownloadManager";
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 30000; // 下载需要更长的超时时间
    private static final int BUFFER_SIZE = 8192;
    
    private final Context context;
    private final ExecutorService executorService;
    private final Handler mainHandler;
//    private final ApiConfig apiConfig;
    
    public DownloadManager(Context context) {
        this.context = context.getApplicationContext();
        this.executorService = Executors.newCachedThreadPool();
        this.mainHandler = new Handler(Looper.getMainLooper());
//        this.apiConfig = ApiConfig.getInstance(context);
    }
    
    /**
     * 下载文件（带进度回调）
     */
    public void downloadFile(String url, String fileName, String downloadPath, 
                           Map<String, String> headers, DownloadCallback callback) {
        executorService.execute(() -> {
            try {
                TimberLogger.d(TAG, "Starting file download: %s", url);
                performDownload(url, fileName, downloadPath, headers, callback);
            } catch (Exception e) {
                String errorMessage = String.format(context.getString(R.string.download_failed), e.getMessage());
                TimberLogger.e(TAG, errorMessage);
                mainHandler.post(() -> callback.onError(new NetworkException(errorMessage, e)));
            }
        });
    }
    
    /**
     * 下载文件（使用基础URL）
     */
    public void downloadFileWithEndpoint(String endpoint, String fileName, String downloadPath, 
                                       Map<String, String> headers, DownloadCallback callback) {
        downloadFile(endpoint, fileName, downloadPath, headers, callback);
    }
    
    /**
     * 执行下载
     */
    private void performDownload(String urlString, String fileName, String downloadPath,
                               Map<String, String> headers, DownloadCallback callback) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        try {
            // 设置连接属性
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            
            // 设置请求头
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            
            // 获取文件大小
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP error: " + responseCode);
            }
            
            long fileSize = connection.getContentLengthLong();
            TimberLogger.d(TAG, "File size: %d bytes", fileSize);
            
            // 确保下载目录存在
            File downloadDir = new File(downloadPath);
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }
            
            // 生成最终文件名
            String finalFileName = generateUniqueFileName(downloadPath, fileName);
            File outputFile = new File(downloadPath, finalFileName);
            
            // 开始下载
            InputStream inputStream = connection.getInputStream();
            try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                long totalBytesRead = 0;
                long lastProgressUpdate = 0;
                
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    
                    // 更新进度（每1%或每MB更新一次）
                    if (fileSize > 0) {
                        int progress = (int) ((totalBytesRead * 100) / fileSize);
                        long currentTime = System.currentTimeMillis();
                        
                        if (progress > lastProgressUpdate || 
                            (currentTime - lastProgressUpdate) > 1000 || // 每秒更新一次
                            totalBytesRead % (1024 * 1024) == 0) { // 每MB更新一次
                            
                            final int finalProgress = progress;
                            final long finalTotalBytes = totalBytesRead;
                            
                            mainHandler.post(() -> callback.onProgress(finalProgress, finalTotalBytes, fileSize));
                            lastProgressUpdate = progress;
                        }
                    } else {
                        // 如果无法获取文件大小，按字节数更新
                        if (totalBytesRead % (1024 * 1024) == 0) { // 每MB更新一次
                            final long finalTotalBytes = totalBytesRead;
                            mainHandler.post(() -> callback.onProgress(-1, finalTotalBytes, -1));
                        }
                    }
                }
                
                // 下载完成
                final String finalFilePath = outputFile.getAbsolutePath();
                mainHandler.post(() -> {
                    callback.onProgress(100, fileSize, fileSize);
                    callback.onSuccess(finalFilePath, fileSize);
                });
                
                TimberLogger.d(TAG, "Download completed: %s (%d bytes)", finalFilePath, fileSize);
                
            } finally {
                inputStream.close();
            }
            
        } finally {
            connection.disconnect();
        }
    }
    
    /**
     * 生成唯一的文件名
     */
    private String generateUniqueFileName(String downloadPath, String originalFileName) {
        if (originalFileName == null || originalFileName.isEmpty()) {
            originalFileName = "download_" + System.currentTimeMillis();
        }
        
        File file = new File(downloadPath, originalFileName);
        if (!file.exists()) {
            return originalFileName;
        }
        
        // 如果文件已存在，添加数字后缀
        String nameWithoutExt = originalFileName;
        String extension = "";
        
        int lastDotIndex = originalFileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            nameWithoutExt = originalFileName.substring(0, lastDotIndex);
            extension = originalFileName.substring(lastDotIndex);
        }
        
        int counter = 1;
        String newFileName;
        do {
            newFileName = nameWithoutExt + "_" + counter + extension;
            file = new File(downloadPath, newFileName);
            counter++;
        } while (file.exists());
        
        return newFileName;
    }
    
    /**
     * 检查文件是否已存在
     */
    public boolean isFileExists(String fileName, String downloadPath) {
        File file = new File(downloadPath, fileName);
        return file.exists();
    }
    
    /**
     * 获取已下载文件的大小
     */
    public long getFileSize(String fileName, String downloadPath) {
        File file = new File(downloadPath, fileName);
        return file.exists() ? file.length() : 0;
    }
    
    /**
     * 删除下载的文件
     */
    public boolean deleteDownloadedFile(String fileName, String downloadPath) {
        File file = new File(downloadPath, fileName);
        return file.exists() && file.delete();
    }
    
    /**
     * 关闭下载管理器
     */
    public void shutdown() {
        executorService.shutdown();
    }
    
    /**
     * 下载回调接口
     */
    public interface DownloadCallback {
        /**
         * 下载进度回调
         * @param progress 进度百分比 (0-100)，-1表示无法计算进度
         * @param bytesDownloaded 已下载字节数
         * @param totalBytes 总字节数，-1表示未知
         */
        void onProgress(int progress, long bytesDownloaded, long totalBytes);
        
        /**
         * 下载成功回调
         * @param filePath 下载文件的完整路径
         * @param fileSize 文件大小
         */
        void onSuccess(String filePath, long fileSize);
        
        /**
         * 下载错误回调
         * @param exception 错误信息
         */
        void onError(NetworkException exception);
    }
    
    /**
     * 下载信息类
     */
    public static class DownloadInfo {
        private final String url;
        private final String fileName;
        private final String downloadPath;
        private final long fileSize;
        private final long downloadedBytes;
        private final int progress;
        private final String filePath;
        
        public DownloadInfo(String url, String fileName, String downloadPath, 
                          long fileSize, long downloadedBytes, int progress, String filePath) {
            this.url = url;
            this.fileName = fileName;
            this.downloadPath = downloadPath;
            this.fileSize = fileSize;
            this.downloadedBytes = downloadedBytes;
            this.progress = progress;
            this.filePath = filePath;
        }
        
        public String getUrl() { return url; }
        public String getFileName() { return fileName; }
        public String getDownloadPath() { return downloadPath; }
        public long getFileSize() { return fileSize; }
        public long getDownloadedBytes() { return downloadedBytes; }
        public int getProgress() { return progress; }
        public String getFilePath() { return filePath; }
        
        @Override
        public String toString() {
            return String.format("DownloadInfo{url='%s', fileName='%s', progress=%d%%, downloaded=%d/%d bytes}",
                    url, fileName, progress, downloadedBytes, fileSize);
        }
    }
} 