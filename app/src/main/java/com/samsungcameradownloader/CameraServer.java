package com.samsungcameradownloader;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class CameraServer {
    private static final int PORT = 8080;
    private boolean isRunning = false;
    private ServerSocket serverSocket;
    private final Context context;

    public CameraServer(Context context) {
        this.context = context;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.d("CameraServer", "Servidor listo en puerto " + PORT);
                while (isRunning) {
                    Socket clientSocket = serverSocket.accept();
                    handleClient(clientSocket);
                }
            } catch (Exception e) {
                Log.e("CameraServer", "Error en el servidor: " + e.getMessage());
            }
        }).start();
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
    }

    private void handleClient(Socket socket) {
        new Thread(() -> {
            try (InputStream input = socket.getInputStream();
                 OutputStream output = socket.getOutputStream()) {

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] data = new byte[8192];
                int bytesRead;

                while ((bytesRead = input.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, bytesRead);
                    if (input.available() == 0) break;
                }

                byte[] rawBytes = buffer.toByteArray();
                if (rawBytes.length > 0) {
                    saveImageToGallery(rawBytes);
                }

                String response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                output.write(response.getBytes());
                output.flush();

            } catch (Exception e) {
                Log.e("CameraServer", "Error al procesar recepcion: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }).start();
    }

    private void saveImageToGallery(byte[] imageBytes) {
        try {
            int headerEndIndex = findHeaderEnd(imageBytes);
            if (headerEndIndex == -1) return;

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "WB35F_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SamsungCamera");

            Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                    out.write(imageBytes, headerEndIndex, imageBytes.length - headerEndIndex);
                    out.flush();
                }
                Log.d("CameraServer", "Imagen guardada correctamente en la galeria");
            }
        } catch (Exception e) {
            Log.e("CameraServer", "Error al guardar archivo: " + e.getMessage());
        }
    }

    private int findHeaderEnd(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }
}
