import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * File Sharing Server
 * Handles multiple client connections and provides file upload/download services
 * 
 * Features:
 * - Multi-threaded server using thread pool
 * - File upload, download, list, and delete operations
 * - Automatic shared folder creation
 * - Progress tracking for file transfers
 * - Graceful shutdown handling
 */
public class FileServer {
    private static final int PORT = 8080;
    private static final String SHARED_FOLDER = "shared_files";
    private static final int THREAD_POOL_SIZE = 10;
    private static final int BUFFER_SIZE = 4096;
    
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean isRunning = false;
    
    public FileServer() {
        threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        createSharedFolder();
    }
    
    /**
     * Creates the shared folder if it doesn't exist
     */
    private void createSharedFolder() {
        File folder = new File(SHARED_FOLDER);
        if (!folder.exists()) {
            if (folder.mkdir()) {
                System.out.println("Created shared folder: " + SHARED_FOLDER);
            } else {
                System.err.println("Failed to create shared folder: " + SHARED_FOLDER);
            }
        } else {
            System.out.println("Using existing shared folder: " + SHARED_FOLDER);
        }
    }
    
    /**
     * Starts the server and listens for client connections
     */
    public void start() {
        try {
            serverSocket = new ServerSocket(PORT);
            isRunning = true;
            
            System.out.println("===================================");
            System.out.println("    File Sharing Server Started    ");
            System.out.println("===================================");
            System.out.println("Port: " + PORT);
            System.out.println("Shared folder: " + new File(SHARED_FOLDER).getAbsolutePath());
            System.out.println("Max concurrent clients: " + THREAD_POOL_SIZE);
            System.out.println("Server is ready to accept connections...");
            System.out.println("Press Ctrl+C to stop the server");
            System.out.println("===================================\n");
            
            while (isRunning && !serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("New client connected: " + clientSocket.getRemoteSocketAddress());
                    threadPool.submit(new ClientHandler(clientSocket));
                } catch (IOException e) {
                    if (isRunning) {
                        System.err.println("Error accepting client connection: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Server startup error: " + e.getMessage());
        }
    }
    
    /**
     * Stops the server gracefully
     */
    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            threadPool.shutdown();
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
            System.out.println("Server stopped successfully.");
        } catch (IOException | InterruptedException e) {
            System.err.println("Error stopping server: " + e.getMessage());
            threadPool.shutdownNow();
        }
    }
    
    /**
     * Handles individual client connections
     */
    private class ClientHandler implements Runnable {
        private Socket clientSocket;
        private DataInputStream dataIn;
        private DataOutputStream dataOut;
        private String clientAddress;
        
        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
            this.clientAddress = socket.getRemoteSocketAddress().toString();
        }
        
        @Override
        public void run() {
            try {
                dataIn = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream()));
                dataOut = new DataOutputStream(new BufferedOutputStream(clientSocket.getOutputStream()));
                
                System.out.println("Client handler started for: " + clientAddress);
                
                String command;
                while ((command = dataIn.readUTF()) != null) {
                    System.out.println("[" + clientAddress + "] Command: " + command);
                    
                    switch (command.toUpperCase().trim()) {
                        case "LIST":
                            handleListFiles();
                            break;
                        case "UPLOAD":
                            handleFileUpload();
                            break;
                        case "DOWNLOAD":
                            handleFileDownload();
                            break;
                        case "DELETE":
                            handleFileDelete();
                            break;
                        case "QUIT":
                            dataOut.writeUTF("Goodbye! Connection closed.");
                            dataOut.flush();
                            System.out.println("[" + clientAddress + "] Client disconnected gracefully");
                            return;
                        default:
                            dataOut.writeUTF("ERROR: Unknown command. Available: LIST, UPLOAD, DOWNLOAD, DELETE, QUIT");
                            dataOut.flush();
                    }
                }
                
            } catch (EOFException e) {
                System.out.println("[" + clientAddress + "] Client disconnected");
            } catch (IOException e) {
                System.out.println("[" + clientAddress + "] Connection error: " + e.getMessage());
            } finally {
                cleanup();
            }
        }
        
        /**
         * Handles file listing request
         */
        private void handleListFiles() throws IOException {
            File folder = new File(SHARED_FOLDER);
            File[] files = folder.listFiles();
            
            if (files == null || files.length == 0) {
                dataOut.writeUTF("No files available on the server.");
                dataOut.flush();
                return;
            }
            
            StringBuilder fileList = new StringBuilder();
            fileList.append("Files available on server:\n");
            fileList.append("=" .repeat(40)).append("\n");
            
            long totalSize = 0;
            int fileCount = 0;
            
            for (File file : files) {
                if (file.isFile()) {
                    long fileSize = file.length();
                    fileList.append(String.format("%-30s %10s\n", 
                        file.getName(), 
                        formatFileSize(fileSize)));
                    totalSize += fileSize;
                    fileCount++;
                }
            }
            
            fileList.append("=" .repeat(40)).append("\n");
            fileList.append(String.format("Total: %d files, %s", fileCount, formatFileSize(totalSize)));
            
            dataOut.writeUTF(fileList.toString());
            dataOut.flush();
            
            System.out.println("[" + clientAddress + "] Listed " + fileCount + " files");
        }
        
        /**
         * Handles file upload request
         */
        private void handleFileUpload() throws IOException {
            String fileName = dataIn.readUTF();
            long fileSize = dataIn.readLong();
            
            // Validate filename
            if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                dataOut.writeUTF("ERROR: Invalid filename");
                dataOut.flush();
                return;
            }
            
            File uploadFile = new File(SHARED_FOLDER, fileName);
            System.out.println("[" + clientAddress + "] Uploading: " + fileName + " (" + formatFileSize(fileSize) + ")");
            
            try (FileOutputStream fos = new FileOutputStream(uploadFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                
                byte[] buffer = new byte[BUFFER_SIZE];
                long totalReceived = 0;
                int bytesRead;
                long lastProgressUpdate = System.currentTimeMillis();
                
                while (totalReceived < fileSize) {
                    int toRead = (int) Math.min(buffer.length, fileSize - totalReceived);
                    bytesRead = dataIn.read(buffer, 0, toRead);
                    
                    if (bytesRead == -1) {
                        throw new IOException("Unexpected end of stream");
                    }
                    
                    bos.write(buffer, 0, bytesRead);
                    totalReceived += bytesRead;
                    
                    // Progress update every second
                    if (System.currentTimeMillis() - lastProgressUpdate > 1000) {
                        int progress = (int) ((totalReceived * 100) / fileSize);
                        System.out.println("[" + clientAddress + "] Upload progress: " + progress + "%");
                        lastProgressUpdate = System.currentTimeMillis();
                    }
                }
                
                dataOut.writeUTF("SUCCESS: File uploaded successfully - " + fileName);
                dataOut.flush();
                
                System.out.println("[" + clientAddress + "] Upload completed: " + fileName);
                
            } catch (Exception e) {
                // Clean up partial file
                if (uploadFile.exists()) {
                    uploadFile.delete();
                }
                dataOut.writeUTF("ERROR: Upload failed - " + e.getMessage());
                dataOut.flush();
                System.err.println("[" + clientAddress + "] Upload failed: " + e.getMessage());
            }
        }
        
        /**
         * Handles file download request
         */
        private void handleFileDownload() throws IOException {
            String fileName = dataIn.readUTF();
            File file = new File(SHARED_FOLDER, fileName);
            
            if (!file.exists() || !file.isFile()) {
                dataOut.writeUTF("ERROR");
                dataOut.writeUTF("File not found: " + fileName);
                dataOut.flush();
                return;
            }
            
            System.out.println("[" + clientAddress + "] Downloading: " + fileName + " (" + formatFileSize(file.length()) + ")");
            
            dataOut.writeUTF("SUCCESS");
            dataOut.writeLong(file.length());
            dataOut.flush();
            
            try (FileInputStream fis = new FileInputStream(file);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {
                
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                long totalSent = 0;
                long lastProgressUpdate = System.currentTimeMillis();
                
                while ((bytesRead = bis.read(buffer)) > 0) {
                    dataOut.write(buffer, 0, bytesRead);
                    totalSent += bytesRead;
                    
                    // Progress update every second
                    if (System.currentTimeMillis() - lastProgressUpdate > 1000) {
                        int progress = (int) ((totalSent * 100) / file.length());
                        System.out.println("[" + clientAddress + "] Download progress: " + progress + "%");
                        lastProgressUpdate = System.currentTimeMillis();
                    }
                }
                
                dataOut.flush();
                System.out.println("[" + clientAddress + "] Download completed: " + fileName);
                
            } catch (Exception e) {
                System.err.println("[" + clientAddress + "] Download error: " + e.getMessage());
            }
        }
        
        /**
         * Handles file deletion request
         */
        private void handleFileDelete() throws IOException {
            String fileName = dataIn.readUTF();
            File file = new File(SHARED_FOLDER, fileName);
            
            if (!file.exists()) {
                dataOut.writeUTF("ERROR: File not found - " + fileName);
                dataOut.flush();
                return;
            }
            
            if (file.delete()) {
                dataOut.writeUTF("SUCCESS: File deleted successfully - " + fileName);
                System.out.println("[" + clientAddress + "] Deleted file: " + fileName);
            } else {
                dataOut.writeUTF("ERROR: Could not delete file - " + fileName);
            }
            dataOut.flush();
        }
        
        /**
         * Cleanup resources
         */
        private void cleanup() {
            try {
                if (dataIn != null) dataIn.close();
                if (dataOut != null) dataOut.close();
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                }
            } catch (IOException e) {
                System.err.println("Error during cleanup: " + e.getMessage());
            }
        }
        
        /**
         * Format file size in human readable format
         */
        private String formatFileSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            int exp = (int) (Math.log(bytes) / Math.log(1024));
            String pre = "KMGTPE".charAt(exp - 1) + "";
            return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
        }
    }
    
    /**
     * Main method to start the server
     */
    public static void main(String[] args) {
        FileServer server = new FileServer();
        
        // Add shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutdown signal received. Stopping server...");
            server.stop();
        }));
        
        // Start the server
        server.start();
    }
}