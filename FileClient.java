import java.io.*;
import java.net.*;
import java.util.Scanner;

/**
 * File Sharing Client
 * Connects to FileServer and provides command-line interface for file operations
 * 
 * Features:
 * - File upload with progress tracking
 * - File download with progress tracking
 * - File listing and deletion
 * - User-friendly command-line interface
 * - Automatic download folder creation
 * - Input validation and error handling
 */
public class FileClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8080;
    private static final String DOWNLOAD_FOLDER = "downloads";
    private static final int BUFFER_SIZE = 4096;
    
    private Socket socket;
    private DataInputStream dataIn;
    private DataOutputStream dataOut;
    private Scanner scanner;
    private boolean isConnected = false;
    
    public FileClient() {
        scanner = new Scanner(System.in);
        createDownloadFolder();
    }
    
    /**
     * Creates the download folder if it doesn't exist
     */
    private void createDownloadFolder() {
        File folder = new File(DOWNLOAD_FOLDER);
        if (!folder.exists()) {
            if (folder.mkdir()) {
                System.out.println("Created download folder: " + DOWNLOAD_FOLDER);
            } else {
                System.err.println("Failed to create download folder: " + DOWNLOAD_FOLDER);
            }
        }
    }
    
    /**
     * Connects to the server
     */
    public boolean connect() {
        try {
            System.out.println("Connecting to server at " + SERVER_HOST + ":" + SERVER_PORT + "...");
            socket = new Socket(SERVER_HOST, SERVER_PORT);
            dataIn = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            dataOut = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            
            isConnected = true;
            System.out.println("Successfully connected to server!");
            return true;
            
        } catch (IOException e) {
            System.err.println("Failed to connect to server: " + e.getMessage());
            System.err.println("Make sure the server is running on " + SERVER_HOST + ":" + SERVER_PORT);
            return false;
        }
    }
    
    /**
     * Disconnects from the server
     */
    public void disconnect() {
        if (!isConnected) return;
        
        try {
            if (dataOut != null) {
                dataOut.writeUTF("QUIT");
                dataOut.flush();
                
                // Read server response
                if (dataIn != null) {
                    String response = dataIn.readUTF();
                    System.out.println("Server: " + response);
                }
            }
        } catch (IOException e) {
            System.err.println("Error during disconnect: " + e.getMessage());
        } finally {
            cleanup();
        }
    }
    
    /**
     * Cleanup resources
     */
    private void cleanup() {
        isConnected = false;
        try {
            if (dataIn != null) dataIn.close();
            if (dataOut != null) dataOut.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            System.err.println("Error during cleanup: " + e.getMessage());
        }
    }
    
    /**
     * Main client loop
     */
    public void start() {
        if (!connect()) {
            return;
        }
        
        showWelcomeMessage();
        showMenu();
        
        String choice;
        while (isConnected) {
            System.out.print("\n> Enter your choice (1-6): ");
            choice = scanner.nextLine().trim();
            
            if (choice.isEmpty()) {
                continue;
            }
            
            switch (choice) {
                case "1":
                    listFiles();
                    break;
                case "2":
                    uploadFile();
                    break;
                case "3":
                    downloadFile();
                    break;
                case "4":
                    deleteFile();
                    break;
                case "5":
                    showMenu();
                    break;
                case "6":
                    disconnect();
                    System.out.println("Goodbye!");
                    return;
                default:
                    System.out.println("Invalid choice. Please enter a number between 1-6.");
                    System.out.println("Type '5' to show the menu again.");
            }
        }
    }
    
    /**
     * Display welcome message
     */
    private void showWelcomeMessage() {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("         FILE SHARING CLIENT");
        System.out.println("=".repeat(50));
        System.out.println("Connected to: " + SERVER_HOST + ":" + SERVER_PORT);
        System.out.println("Download folder: " + new File(DOWNLOAD_FOLDER).getAbsolutePath());
        System.out.println("=".repeat(50));
    }
    
    /**
     * Display menu options
     */
    private void showMenu() {
        System.out.println("\nAvailable commands:");
        System.out.println("1. List files on server");
        System.out.println("2. Upload file to server");
        System.out.println("3. Download file from server");
        System.out.println("4. Delete file from server");
        System.out.println("5. Show this menu");
        System.out.println("6. Quit");
    }
    
    /**
     * List files on server
     */
    private void listFiles() {
        if (!isConnected) return;
        
        try {
            dataOut.writeUTF("LIST");
            dataOut.flush();
            
            String response = dataIn.readUTF();
            System.out.println("\n" + response);
            
        } catch (IOException e) {
            System.err.println("Error listing files: " + e.getMessage());
            handleConnectionError();
        }
    }
    
    /**
     * Upload file to server
     */
    private void uploadFile() {
        if (!isConnected) return;
        
        System.out.print("\nEnter the full path of the file to upload: ");
        String filePath = scanner.nextLine().trim();
        
        if (filePath.isEmpty()) {
            System.out.println("File path cannot be empty.");
            return;
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("File not found: " + filePath);
            return;
        }
        
        if (!file.isFile()) {
            System.out.println("Path is not a file: " + filePath);
            return;
        }
        
        if (file.length() == 0) {
            System.out.println("Cannot upload empty file.");
            return;
        }
        
        // Confirm upload
        System.out.println("File: " + file.getName());
        System.out.println("Size: " + formatFileSize(file.length()));
        System.out.print("Proceed with upload? (y/N): ");
        String confirm = scanner.nextLine().trim();
        
        if (!"y".equalsIgnoreCase(confirm) && !"yes".equalsIgnoreCase(confirm)) {
            System.out.println("Upload cancelled.");
            return;
        }
        
        try {
            dataOut.writeUTF("UPLOAD");
            dataOut.writeUTF(file.getName());
            dataOut.writeLong(file.length());
            dataOut.flush();
            
            try (FileInputStream fis = new FileInputStream(file);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {
                
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                long totalSent = 0;
                long fileSize = file.length();
                long startTime = System.currentTimeMillis();
                
                System.out.println("\nUploading " + file.getName() + "...");
                System.out.println("Progress: 0%");
                
                while ((bytesRead = bis.read(buffer)) > 0) {
                    dataOut.write(buffer, 0, bytesRead);
                    totalSent += bytesRead;
                    
                    // Show progress
                    int progress = (int) ((totalSent * 100) / fileSize);
                    long elapsed = System.currentTimeMillis() - startTime;
                    double speed = (totalSent / 1024.0) / (elapsed / 1000.0); // KB/s
                    
                    System.out.print("\rProgress: " + progress + "% (" + 
                                   formatFileSize(totalSent) + "/" + formatFileSize(fileSize) + 
                                   " @ " + String.format("%.1f KB/s", speed) + ")");
                }
                
                dataOut.flush();
                System.out.println(); // New line after progress
                
                String response = dataIn.readUTF();
                System.out.println("Server response: " + response);
                
            }
            
        } catch (IOException e) {
            System.err.println("Error uploading file: " + e.getMessage());
            handleConnectionError();
        }
    }
    
    /**
     * Download file from server
     */
    private void downloadFile() {
        if (!isConnected) return;
        
        System.out.print("\nEnter the name of the file to download: ");
        String fileName = scanner.nextLine().trim();
        
        if (fileName.isEmpty()) {
            System.out.println("Filename cannot be empty.");
            return;
        }
        
        // Check if file already exists locally
        File localFile = new File(DOWNLOAD_FOLDER, fileName);
        if (localFile.exists()) {
            System.out.print("File already exists locally. Overwrite? (y/N): ");
            String confirm = scanner.nextLine().trim();
            if (!"y".equalsIgnoreCase(confirm) && !"yes".equalsIgnoreCase(confirm)) {
                System.out.println("Download cancelled.");
                return;
            }
        }
        
        try {
            dataOut.writeUTF("DOWNLOAD");
            dataOut.writeUTF(fileName);
            dataOut.flush();
            
            String status = dataIn.readUTF();
            
            if ("ERROR".equals(status)) {
                String errorMsg = dataIn.readUTF();
                System.out.println("Download failed: " + errorMsg);
                return;
            }
            
            long fileSize = dataIn.readLong();
            File downloadFile = new File(DOWNLOAD_FOLDER, fileName);
            
            try (FileOutputStream fos = new FileOutputStream(downloadFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                
                byte[] buffer = new byte[BUFFER_SIZE];
                long totalReceived = 0;
                int bytesRead;
                long startTime = System.currentTimeMillis();
                
                System.out.println("\nDownloading " + fileName + " (" + formatFileSize(fileSize) + ")...");
                System.out.println("Progress: 0%");
                
                while (totalReceived < fileSize) {
                    int toRead = (int) Math.min(buffer.length, fileSize - totalReceived);
                    bytesRead = dataIn.read(buffer, 0, toRead);
                    
                    if (bytesRead == -1) {
                        throw new IOException("Unexpected end of stream");
                    }
                    
                    bos.write(buffer, 0, bytesRead);
                    totalReceived += bytesRead;
                    
                    // Show progress
                    int progress = (int) ((totalReceived * 100) / fileSize);
                    long elapsed = System.currentTimeMillis() - startTime;
                    double speed = (totalReceived / 1024.0) / (elapsed / 1000.0); // KB/s
                    
                    System.out.print("\rProgress: " + progress + "% (" + 
                                   formatFileSize(totalReceived) + "/" + formatFileSize(fileSize) + 
                                   " @ " + String.format("%.1f KB/s", speed) + ")");
                }
                
                System.out.println(); // New line after progress
                System.out.println("File downloaded successfully!");
                System.out.println("Saved to: " + downloadFile.getAbsolutePath());
                
            }
            
        } catch (IOException e) {
            System.err.println("Error downloading file: " + e.getMessage());
            handleConnectionError();
        }
    }
    
    /**
     * Delete file from server
     */
    private void deleteFile() {
        if (!isConnected) return;
        
        System.out.print("\nEnter the name of the file to delete: ");
        String fileName = scanner.nextLine().trim();
        
        if (fileName.isEmpty()) {
            System.out.println("Filename cannot be empty.");
            return;
        }
        
        System.out.print("Are you sure you want to delete '" + fileName + "' from the server? (y/N): ");
        String confirm = scanner.nextLine().trim();
        
        if (!"y".equalsIgnoreCase(confirm) && !"yes".equalsIgnoreCase(confirm)) {
            System.out.println("Delete cancelled.");
            return;
        }
        
        try {
            dataOut.writeUTF("DELETE");
            dataOut.writeUTF(fileName);
            dataOut.flush();
            
            String response = dataIn.readUTF();
            System.out.println("Server response: " + response);
            
        } catch (IOException e) {
            System.err.println("Error deleting file: " + e.getMessage());
            handleConnectionError();
        }
    }
    
    /**
     * Handle connection errors
     */
    private void handleConnectionError() {
        System.err.println("Connection to server lost. Please restart the client.");
        isConnected = false;
        cleanup();
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
    
    /**
     * Main method to start the client
     */
    public static void main(String[] args) {
        FileClient client = new FileClient();
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down client...");
            client.cleanup();
        }));
        
        try {
            client.start();
        } catch (Exception e) {
            System.err.println("Client error: " + e.getMessage());
        } finally {
            client.cleanup();
        }
    }
}